package org.cfm.catscriptor.app

import org.cfm.catscriptor.domain.app.CorrectionLevel
import org.cfm.catscriptor.domain.app.CorrectionProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import org.cfm.catscriptor.domain.app.AppSettings
import org.cfm.catscriptor.domain.app.ExportFormat
import org.cfm.catscriptor.domain.app.ExportGateway
import org.cfm.catscriptor.domain.app.ExportOptions
import org.cfm.catscriptor.domain.app.ImportResult
import org.cfm.catscriptor.domain.app.ModelsStatus
import org.cfm.catscriptor.domain.app.PlaybackController
import org.cfm.catscriptor.domain.app.PlaybackState
import org.cfm.catscriptor.domain.app.ProcessingController
import org.cfm.catscriptor.domain.app.ProcessingJob
import org.cfm.catscriptor.domain.app.ProcessingStage
import org.cfm.catscriptor.domain.app.RecorderController
import org.cfm.catscriptor.domain.app.RecorderState
import org.cfm.catscriptor.domain.app.RecordingQuery
import org.cfm.catscriptor.domain.app.RecordingRepository
import org.cfm.catscriptor.domain.app.RecordingSummary
import org.cfm.catscriptor.domain.app.SettingsRepository
import org.cfm.catscriptor.domain.app.SharePayload
import org.cfm.catscriptor.domain.app.StartRecordingResult
import org.cfm.catscriptor.domain.app.TranscriptDocument

/**
 * Честное состояние основной сборки, пока пайплайн (#3–#8) не реализован: записей нет,
 * модели недоступны. Здесь нет выдуманных данных; экран показывает состояния ошибок.
 */
class UnavailableAppGraph : AppGraph {
    override val recordings: RecordingRepository = EmptyRecordingRepository
    override val processing: ProcessingController = UnavailableProcessingController()
    override val recorder: RecorderController = UnavailableRecorderController()
    override val playback: PlaybackController = UnavailablePlaybackController()
    override val export: ExportGateway = UnavailableExportGateway
    override val settings: SettingsRepository = InMemorySettingsRepository()
}

/** Настройки живут только в памяти процесса; постоянное хранилище появится вместе с логикой. */
class InMemorySettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    override fun update(transform: (AppSettings) -> AppSettings) = state.update(transform)
}

private object EmptyRecordingRepository : RecordingRepository {
    override fun observe(query: RecordingQuery): Flow<List<RecordingSummary>> = flowOf(emptyList())
    override fun observeTranscript(recordingId: String): Flow<TranscriptDocument?> = flowOf(null)
    override suspend fun rename(recordingId: String, title: String) = Unit
    override suspend fun delete(recordingId: String) = Unit
    override suspend fun restore(recordingId: String) = Unit
    override suspend fun commitDelete(recordingId: String) = Unit
    override suspend fun renameSpeaker(recordingId: String, speakerId: String, displayName: String) = Unit
    override suspend fun editLineText(recordingId: String, lineId: String, text: String) = Unit
    override suspend fun changeLineSpeaker(recordingId: String, lineId: String, speakerId: String) = Unit
    override suspend fun saveNote(recordingId: String, noteId: String?, positionMs: Long, text: String) = Unit
    override suspend fun deleteNote(recordingId: String, noteId: String) = Unit
}

private class UnavailableProcessingController : ProcessingController {
    override val modelsStatus: StateFlow<ModelsStatus> =
        MutableStateFlow(ModelsStatus.Unavailable(details = null))
    override val correction: StateFlow<CorrectionProgress?> = MutableStateFlow(null)

    override fun observeJobs(): Flow<List<ProcessingJob>> = flowOf(emptyList())
    override suspend fun importFile(documentUri: String, displayName: String) = ImportResult.ModelsUnavailable
    override suspend fun cancel(recordingId: String) = Unit
    override suspend fun retry(recordingId: String) = Unit
    override suspend fun correctText(recordingId: String, level: CorrectionLevel) { error("Модели недоступны") }
    override suspend fun skipStage(recordingId: String, stage: ProcessingStage) = Unit
}

/** internal: переиспользуются ProductionAppGraph до реализации #20/#21. */
internal class UnavailableRecorderController : RecorderController {
    override suspend fun cancel() = Unit
    override val isAvailable = false
    override val state: StateFlow<RecorderState> = MutableStateFlow(RecorderState.Idle)
    override suspend fun start(liveText: Boolean) = StartRecordingResult.Unavailable
    override suspend fun stop(): String? = null
    override fun pause() = Unit
    override fun resume() = Unit
    override fun setLiveText(enabled: Boolean) = Unit
    override fun addMark() = Unit
    override fun addNote(text: String, positionMs: Long?) = Unit
    override fun deleteNote(noteId: String) = Unit
}

/** Плеер графа без бэкенда (UnavailableAppGraph). */
internal class UnavailablePlaybackController : PlaybackController {
    override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())
    override fun load(recordingId: String) = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setSpeed(speed: Float) = Unit
    override fun release(recordingId: String) = Unit
}

/** Без расшифровок экспортировать нечего (UnavailableAppGraph). */
internal object UnavailableExportGateway : ExportGateway {
    override suspend fun preview(recordingId: String, options: ExportOptions): String = ""
    override suspend fun saveTo(documentUri: String, recordingId: String, options: ExportOptions): String =
        error("Экспорт недоступен: нет расшифровок")
    override suspend fun prepareShare(recordingId: String, options: ExportOptions): SharePayload =
        error("Экспорт недоступен: нет расшифровок")
    override fun suggestedFileName(title: String, format: ExportFormat): String = title
}
