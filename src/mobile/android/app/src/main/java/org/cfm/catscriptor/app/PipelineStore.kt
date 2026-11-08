package org.cfm.catscriptor.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.cfm.catscriptor.domain.app.RecordingSource
import org.cfm.catscriptor.domain.app.RecordingStatus
import org.cfm.catscriptor.domain.app.RecordingSummary

/**
 * Запись библиотеки вместе со служебным состоянием очереди подготовки.
 * Метаданные и результаты сохраняются отдельно от аудио.
 */
internal data class PipelineEntry(
    val id: String,
    val title: String,
    val documentUri: String,
    val fileExtension: String?,
    val createdAtEpochMs: Long,
    val durationMs: Long,
    val status: RecordingStatus,
    val prepareFailure: String? = null,
    val prepareElapsedMs: Long = 0,
    val hidden: Boolean = false,
    val pendingCommitDelete: Boolean = false,
    val processingState: org.cfm.catscriptor.audio.background.AudioProcessingState? = null,
    /** Пользовательские правки поверх финального транскрипта; null — правок не было. */
    val editedTranscript: List<org.cfm.catscriptor.domain.pipeline.Utterance>? = null,
    /** Заданные пользователем имена спикеров (speaker_N → имя). */
    val speakerDisplayNames: Map<String, String> = emptyMap(),
    val ownedAudioPath: String? = null,
    val deletedAtEpochMs: Long? = null,
    val notes: List<org.cfm.catscriptor.domain.app.RecordingNote> = emptyList(),
    val textCorrected: Boolean = false,
) {
    /** Транскрипт для показа: правки пользователя поверх результата пайплайна. */
    fun displayedTranscript(): List<org.cfm.catscriptor.domain.pipeline.Utterance> =
        editedTranscript ?: processingState?.transcript.orEmpty()

    fun toSummary() = RecordingSummary(
        id = id,
        title = title,
        source = if (ownedAudioPath == null) RecordingSource.FILE else RecordingSource.MICROPHONE,
        createdAtEpochMs = createdAtEpochMs,
        durationMs = durationMs,
        speakerCount = (displayedTranscript().takeIf { it.isNotEmpty() }
            ?: processingState?.speakerDraft)?.map { it.speakerId }?.distinct()?.size,
        previewSpeakerId = displayedTranscript().firstOrNull()?.speakerId
            ?: processingState?.speakerDraft?.firstOrNull()?.speakerId,
        previewSpeakerName = displayedTranscript().firstOrNull()?.speakerId
            ?.let { speakerDisplayNames[it] },
        previewText = displayedTranscript().firstOrNull()?.text
            ?: processingState?.asrDraft?.firstOrNull()?.text,
        status = status,
        textCorrected = textCorrected,
    )
}

/**
 * Потоки отсюда подписываются UI; все изменения идут копиями LinkedHashMap,
 * чтобы StateFlow замечал обновления. Порядок вставки — порядок очереди.
 */
internal class PipelineStore(private val disk: PipelineLibraryDisk? = null) {
    private val state = MutableStateFlow(disk?.load() ?: LinkedHashMap<String, PipelineEntry>())

    val entries: StateFlow<LinkedHashMap<String, PipelineEntry>> = state.asStateFlow()

    @Synchronized
    fun upsert(entry: PipelineEntry) = publish(LinkedHashMap(state.value).apply { put(entry.id, entry) })

    @Synchronized
    fun update(id: String, transform: (PipelineEntry) -> PipelineEntry) {
        val previous = state.value[id] ?: return
        val next = transform(previous)
        val checkpoint = previous.copy(processingState = next.processingState, status = next.status,
            durationMs = next.durationMs) != next ||
            previous.status::class != next.status::class ||
            previous.processingState?.stage != next.processingState?.stage ||
            previous.processingState?.transcript != next.processingState?.transcript
        publish(LinkedHashMap(state.value).apply { put(id, next) }, checkpoint)
    }

    @Synchronized
    fun remove(id: String) {
        if (state.value.containsKey(id)) publish(LinkedHashMap(state.value).apply { remove(id) })
    }

    private fun publish(next: LinkedHashMap<String, PipelineEntry>, checkpoint: Boolean = true) {
        // Сначала надёжная запись; при ошибке старое состояние остаётся видимым.
        if (checkpoint) disk?.save(next)
        state.value = next
    }

    fun entry(id: String): PipelineEntry? = state.value[id]
}
