package org.cfm.catscriptor.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import org.cfm.catscriptor.domain.app.ExportGateway
import org.cfm.catscriptor.domain.app.PlaybackController
import org.cfm.catscriptor.domain.app.ProcessingController
import org.cfm.catscriptor.domain.app.RecorderController
import org.cfm.catscriptor.domain.app.RecordingRepository
import org.cfm.catscriptor.domain.app.SettingsRepository
import org.cfm.catscriptor.export.ExportAudio
import org.cfm.catscriptor.export.FileExportGateway
import org.cfm.catscriptor.playback.MediaPlaybackController
import org.cfm.catscriptor.playback.MediaPlayerEngine

/**
 * Основной граф: импорт и весь пайплайн (VAD → диаризация → ASR → LLM) работают по-настоящему
 * через AudioProcessingService. Экспортируется только финальная расшифровка; ASR-черновик
 * (если LLM-этап не завершился) — нет. Микрофон сохраняет WAV в приватном хранилище и передаёт
 * content URI в ту же очередь. Плеер играет исходный файл записи через MediaPlayer.
 */
class ProductionAppGraph(context: Context) : AppGraph {
    private val store = PipelineStore(PipelineLibraryDisk(java.io.File(context.filesDir, "library.json")))
    override val settings: SettingsRepository = PersistentSettingsRepository(context.applicationContext)

    private val processingImpl =
        PipelineProcessingController(context.applicationContext, store,
            speakerCount = { settings.settings.value.speakerCount },
            correctionLevel = { settings.settings.value.correctionLevel })
    override val processing: ProcessingController = processingImpl
    override val recorder: RecorderController = org.cfm.catscriptor.recording.androidRecordingController(
        context, processing, saveNotes = { id, notes -> store.update(id) { it.copy(notes = notes) } },
    )
    override val playback: PlaybackController = MediaPlaybackController(
        audioUri = { id -> store.entry(id)?.takeIf { !it.hidden }?.documentUri },
        engineFactory = { MediaPlayerEngine(context.applicationContext) },
        // Плеер однопоточный: движок и его колбэки живут в главном потоке.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )
    override val recordings: RecordingRepository = PipelineRecordingRepository(
        store, java.io.File(context.filesDir, "recordings"),
        beforeDelete = { id ->
            kotlinx.coroutines.withContext(Dispatchers.Main.immediate) { playback.release(id) }
            processingImpl.awaitDeletion(id)
        },
        onRestore = processingImpl::resumeQueue,
    )
    override val export: ExportGateway = FileExportGateway(
        context = context.applicationContext,
        document = { id -> recordings.observeTranscript(id).first() },
        audio = { id -> store.entry(id)?.let { ExportAudio(it.documentUri, it.fileExtension) } },
        // Тот же FileProvider, что отдаёт записи с микрофона; пути см. res/xml/recording_paths.xml.
        authority = "${context.packageName}.recordings",
    )
}
