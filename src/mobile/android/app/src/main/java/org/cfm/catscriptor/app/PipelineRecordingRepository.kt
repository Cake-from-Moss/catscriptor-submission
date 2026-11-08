package org.cfm.catscriptor.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.cfm.catscriptor.domain.app.RecordingFilter
import org.cfm.catscriptor.domain.app.RecordingQuery
import org.cfm.catscriptor.domain.app.RecordingRepository
import org.cfm.catscriptor.domain.app.RecordingStatus
import org.cfm.catscriptor.domain.app.RecordingSummary
import org.cfm.catscriptor.domain.app.SpeakerInfo
import org.cfm.catscriptor.domain.app.TranscriptDocument
import org.cfm.catscriptor.domain.app.TranscriptLine

/**
 * Библиотека записей поверх [PipelineStore]. Готовые записи отдают финальный транскрипт
 * (спикеры + исправленный LLM текст, #6/#7) с возможностью правки реплик и имён спикеров;
 * ASR-черновики доступны только для чтения.
 * Снимок и tombstone сохраняются до публикации UI; незавершённая очистка повторяется при запуске.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineRecordingRepository(
    private val store: PipelineStore,
    private val ownedDirectory: java.io.File? = null,
    private val beforeDelete: suspend (String) -> Unit = {},
    private val onRestore: () -> Unit = {},
) : RecordingRepository {
    private val cleanupScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val mutation = kotlinx.coroutines.sync.Mutex()

    init {
        store.entries.value.values.filter { it.hidden }.forEach { entry ->
            cleanupScope.launch { finishDelete(entry.id, entry.deletedAtEpochMs) }
        }
    }

    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    override fun observe(query: RecordingQuery): Flow<List<RecordingSummary>> =
        store.entries.map { map ->
            map.values.asSequence()
                .filter { !it.hidden && !it.pendingCommitDelete }
                .map { it.toSummary() }
                .filter { matchesFilter(it.status, query.filter) }
                .filter { summary ->
                    query.text.isBlank() ||
                        summary.title.contains(query.text.trim(), ignoreCase = true) ||
                        map[summary.id]?.processingState?.asrDraft.orEmpty().any {
                            it.text.contains(query.text.trim(), ignoreCase = true)
                        }
                }
                .sortedByDescending { it.createdAtEpochMs }
                .toList()
        }

    override fun observeTranscript(recordingId: String): Flow<TranscriptDocument?> = store.entries.map { entries ->
        entries[recordingId]?.takeIf { !it.hidden && it.status == RecordingStatus.Ready }
            ?.let { entry -> finalDocument(entry) }
            ?: entries[recordingId]?.takeIf { !it.hidden && it.status == RecordingStatus.AsrReady }?.let { entry ->
                TranscriptDocument(entry.id, entry.title, entry.createdAtEpochMs, entry.durationMs,
                    emptyList(), emptyList(), asrDraft = entry.processingState?.asrDraft ?: emptyList(),
                    asrDraftSpeakers = entry.processingState?.speakerDraft?.map { it.speakerId },
                    bookmarksMs = entry.notes.map { it.positionMs }.distinct(), notes = entry.notes,
                    textCorrected = entry.textCorrected)
            }
    }

    /** Финальный транскрипт: реплики со спикерами и исправленным текстом, таймкоды исходные. */
    private fun finalDocument(entry: PipelineEntry): TranscriptDocument {
        val utterances = entry.displayedTranscript()
        val totalMs = utterances.sumOf { (it.endMs - it.startMs).coerceAtLeast(0) }.coerceAtLeast(1)
        val speakers = utterances.map { it.speakerId }.distinct().map { id ->
            val shareMs = utterances.filter { it.speakerId == id }.sumOf { (it.endMs - it.startMs).coerceAtLeast(0) }
            SpeakerInfo(id = id, displayName = entry.speakerDisplayNames[id], shareFraction = shareMs.toFloat() / totalMs)
        }
        val lines = utterances.mapIndexed { index, utterance ->
            TranscriptLine(
                id = "line_${utterance.startMs}_$index",
                speakerId = utterance.speakerId,
                startMs = utterance.startMs,
                endMs = utterance.endMs,
                text = utterance.text,
            )
        }
        return TranscriptDocument(
            recordingId = entry.id,
            title = entry.title,
            createdAtEpochMs = entry.createdAtEpochMs,
            durationMs = entry.durationMs,
            speakers = speakers,
            lines = lines,
            bookmarksMs = entry.notes.map { it.positionMs }.distinct(),
            notes = entry.notes,
            textCorrected = entry.textCorrected,
        )
    }

    override suspend fun rename(recordingId: String, title: String) = withContext(dispatcher) {
        val normalized = title.trim()
        require(normalized.isNotEmpty()) { "Введите название записи" }
        store.update(recordingId) { if (it.hidden) it else it.copy(title = normalized) }
    }

    override suspend fun delete(recordingId: String) = withContext(dispatcher) {
        mutation.withLock {
            if (store.entry(recordingId)?.hidden != false) return@withLock
            val token = System.currentTimeMillis()
            store.update(recordingId) { it.copy(hidden = true, pendingCommitDelete = true, deletedAtEpochMs = token) }
            cleanupScope.launch {
                kotlinx.coroutines.delay(5_000)
                finishDelete(recordingId, token)
            }
        }
    }

    override suspend fun restore(recordingId: String) = withContext(dispatcher) {
        mutation.withLock {
            store.update(recordingId) { it.copy(hidden = false, pendingCommitDelete = false, deletedAtEpochMs = null) }
            onRestore()
        }
    }

    override suspend fun commitDelete(recordingId: String) = withContext(dispatcher) {
        // Snackbar не владеет временем жизни удаления: замена сообщения не сокращает окно Undo.
        val entry = store.entry(recordingId) ?: return@withContext
        if (System.currentTimeMillis() - (entry.deletedAtEpochMs ?: return@withContext) >= 5_000)
            finishDelete(recordingId, entry.deletedAtEpochMs)
    }

    private suspend fun finishDelete(id: String, token: Long?) = mutation.withLock {
        val entry = store.entry(id)?.takeIf { it.hidden && it.deletedAtEpochMs == token } ?: return@withLock
        try {
            beforeDelete(id)
            entry.ownedAudioPath?.let { path ->
                val root = checkNotNull(ownedDirectory).canonicalFile
                val file = java.io.File(path).canonicalFile
                check(file.parentFile == root) { "Файл вне каталога записей" }
                check(!file.exists() || file.delete()) { "Не удалось удалить ${file.name}" }
            }
            store.remove(id)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            // Tombstone остаётся на диске: следующий запуск повторит безопасную очистку.
            android.util.Log.e("RecordingCleanup", "Не удалось удалить запись $id", error)
        }
    }

    override suspend fun renameSpeaker(recordingId: String, speakerId: String, displayName: String) =
        withContext(dispatcher) {
            store.update(recordingId) { entry ->
                entry.copy(speakerDisplayNames = entry.speakerDisplayNames + (speakerId to displayName))
            }
        }

    override suspend fun editLineText(recordingId: String, lineId: String, text: String) =
        withContext(dispatcher) {
            store.update(recordingId) { entry ->
                entry.copy(editedTranscript = entry.displayedTranscript().replaceLine(lineId) { it.copy(text = text) })
            }
        }

    override suspend fun changeLineSpeaker(recordingId: String, lineId: String, speakerId: String) =
        withContext(dispatcher) {
            store.update(recordingId) { entry ->
                entry.copy(editedTranscript = entry.displayedTranscript().replaceLine(lineId) { it.copy(speakerId = speakerId) })
            }
        }

    override suspend fun saveNote(recordingId: String, noteId: String?, positionMs: Long, text: String) =
        withContext(dispatcher) {
            store.update(recordingId) { entry ->
                if (entry.hidden) return@update entry
                val existing = noteId?.let { id -> entry.notes.firstOrNull { it.id == id } }
                if (noteId != null && existing == null) return@update entry
                val position = positionMs.coerceAtLeast(0).let {
                    if (entry.durationMs > 0) it.coerceAtMost(entry.durationMs) else it
                }
                val note = org.cfm.catscriptor.domain.app.RecordingNote(
                    noteId ?: java.util.UUID.randomUUID().toString(), position, text.trim().take(2_000))
                entry.copy(notes = (entry.notes.filterNot { it.id == note.id } + note).sortedBy { it.positionMs })
            }
        }

    override suspend fun deleteNote(recordingId: String, noteId: String) = withContext(dispatcher) {
        store.update(recordingId) { entry -> entry.copy(notes = entry.notes.filterNot { it.id == noteId }) }
    }

    /** Правка одной реплики по её идентификатору; неизвестный id — запись без изменений. */
    private fun List<org.cfm.catscriptor.domain.pipeline.Utterance>.replaceLine(
        lineId: String,
        transform: (org.cfm.catscriptor.domain.pipeline.Utterance) -> org.cfm.catscriptor.domain.pipeline.Utterance,
    ): List<org.cfm.catscriptor.domain.pipeline.Utterance> =
        mapIndexed { index, utterance ->
            if ("line_${utterance.startMs}_$index" == lineId) transform(utterance) else utterance
        }

    private fun matchesFilter(status: RecordingStatus, filter: RecordingFilter): Boolean = when (filter) {
        RecordingFilter.ALL -> true
        RecordingFilter.READY -> status == RecordingStatus.Ready || status == RecordingStatus.AsrReady
        RecordingFilter.PROCESSING -> status != RecordingStatus.Ready && status != RecordingStatus.AsrReady
    }
}
