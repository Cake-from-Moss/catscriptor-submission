package org.cfm.catscriptor.app

import android.util.AtomicFile
import androidx.annotation.Keep
import com.google.gson.Gson
import org.cfm.catscriptor.audio.background.AudioProcessingPhase
import org.cfm.catscriptor.audio.background.AudioProcessingState
import org.cfm.catscriptor.domain.app.ProcessingStage
import org.cfm.catscriptor.domain.app.RecordingStatus
import org.cfm.catscriptor.domain.pipeline.AsrSegment
import org.cfm.catscriptor.domain.pipeline.Utterance
import java.io.File

/** Версионированный снимок; полиморфные runtime-статусы не сериализуются. */
internal class PipelineLibraryDisk(file: File) {
    private val atomic = AtomicFile(file)
    private val gson = Gson()
    @Keep
    private data class Snapshot(val version: Int = 1, val entries: List<Row> = emptyList())
    @Keep
    private data class Row(
        val id: String, val title: String, val uri: String, val extension: String?,
        val created: Long, val duration: Long, val status: String, val stage: ProcessingStage,
        val failure: String?, val hidden: Boolean, val deletedAt: Long?, val ownedPath: String?,
        val transcript: List<Utterance>?, val draft: List<Utterance>?, val asr: List<AsrSegment>?,
        val edits: List<Utterance>?, val speakers: Map<String, String>,
        val notes: List<NoteRow>? = null,
        val textCorrected: Boolean? = null,
    )
    @Keep
    private data class NoteRow(val id: String, val positionMs: Long, val text: String)

    fun load(): LinkedHashMap<String, PipelineEntry> {
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return linkedMapOf()
        // Повреждённый/неизвестный формат не заменяется пустой библиотекой.
        val snapshot = atomic.openRead().bufferedReader().use { gson.fromJson(it, Snapshot::class.java) }
        check(snapshot.version == 1) { "Неизвестная версия библиотеки" }
        return LinkedHashMap<String, PipelineEntry>().apply {
            snapshot.entries.forEach { row ->
                val status = when (row.status) {
                    "ready" -> RecordingStatus.Ready
                    "asr" -> RecordingStatus.AsrReady
                    else -> RecordingStatus.Failed(row.stage)
                }
                put(row.id, PipelineEntry(
                    id = row.id, title = row.title, documentUri = row.uri, fileExtension = row.extension,
                    createdAtEpochMs = row.created, durationMs = row.duration, status = status,
                    prepareFailure = if (row.status == "running" || row.status == "queued")
                        "Обработка прервана перезапуском приложения. Повторите обработку." else row.failure,
                    hidden = row.hidden, pendingCommitDelete = row.hidden,
                    processingState = AudioProcessingState(
                        phase = if (status == RecordingStatus.Ready || status == RecordingStatus.AsrReady)
                            AudioProcessingPhase.COMPLETED else AudioProcessingPhase.FAILED,
                        sampleCount = row.duration * 16, stage = row.stage,
                        transcript = row.transcript, speakerDraft = row.draft, asrDraft = row.asr,
                    ),
                    editedTranscript = row.edits, speakerDisplayNames = row.speakers,
                    ownedAudioPath = row.ownedPath, deletedAtEpochMs = row.deletedAt,
                    textCorrected = row.textCorrected ?: false,
                    notes = row.notes.orEmpty().map {
                        org.cfm.catscriptor.domain.app.RecordingNote(it.id, it.positionMs, it.text)
                    },
                ))
            }
        }
    }

    fun save(entries: Map<String, PipelineEntry>) {
        val rows = entries.values.map { entry ->
            Row(entry.id, entry.title, entry.documentUri, entry.fileExtension, entry.createdAtEpochMs,
                entry.durationMs, when (entry.status) {
                    RecordingStatus.Ready -> "ready"
                    RecordingStatus.AsrReady -> "asr"
                    RecordingStatus.Queued -> "queued"
                    is RecordingStatus.Processing -> "running"
                    is RecordingStatus.Failed -> "failed"
                }, entry.processingState?.stage ?: ProcessingStage.PREPARE_AUDIO,
                entry.prepareFailure, entry.hidden, entry.deletedAtEpochMs, entry.ownedAudioPath,
                entry.processingState?.transcript, entry.processingState?.speakerDraft,
                entry.processingState?.asrDraft, entry.editedTranscript, entry.speakerDisplayNames,
                entry.notes.map { NoteRow(it.id, it.positionMs, it.text) }, entry.textCorrected)
        }
        atomic.baseFile.parentFile?.mkdirs()
        val stream = atomic.startWrite()
        try {
            stream.write(gson.toJson(Snapshot(entries = rows)).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }
}
