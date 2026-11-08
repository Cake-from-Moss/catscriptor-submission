package org.cfm.catscriptor.viewmodel.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.cfm.catscriptor.domain.app.ExportFormat
import org.cfm.catscriptor.domain.app.ExportGateway
import org.cfm.catscriptor.domain.app.ExportOptions
import org.cfm.catscriptor.domain.app.RecordingRepository
import org.cfm.catscriptor.domain.app.SettingsRepository
import org.cfm.catscriptor.domain.app.SharePayload
import org.cfm.catscriptor.domain.app.TranscriptDocument

data class ExportUiState(
    val title: String = "",
    val durationMs: Long = 0,
    val speakerCount: Int = 0,
    val options: ExportOptions = ExportOptions(ExportFormat.JSON, useSpeakerNames = true),
    /** null, пока предпросмотр строится. */
    val preview: String? = null,
    val busy: Boolean = false,
    /** false для ASR-черновика: экспортируется только финальная расшифровка. */
    val exportable: Boolean = false,
) {
    /** Кнопки «Поделиться» и «Сохранить» активны только для готовой расшифровки. */
    val actionsEnabled: Boolean get() = exportable && !busy && preview != null

    /** В JSON speaker_id всегда speaker_N, поэтому переключатель имён там ничего не меняет. */
    val speakerNamesApplicable: Boolean get() = options.format != ExportFormat.JSON && !options.audioOnly
}

/** Разовые события для Route: системные окна и Snackbar открывает только UI. */
sealed interface ExportEvent {
    data class Share(val payload: SharePayload) : ExportEvent
    data class Saved(val displayName: String, val documentUri: String, val mimeType: String) : ExportEvent
    data object SaveFailed : ExportEvent
    data object ShareFailed : ExportEvent
}

fun mimeTypeOf(format: ExportFormat): String = format.mimeType

@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModel(
    private val recordingId: String,
    private val gateway: ExportGateway,
    repository: RecordingRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val options = MutableStateFlow(
        ExportOptions(format = settings.settings.value.defaultExportFormat, useSpeakerNames = true),
    )
    private val busy = MutableStateFlow(false)
    private val events = Channel<ExportEvent>(Channel.BUFFERED)

    val eventFlow: Flow<ExportEvent> = events.receiveAsFlow()

    private val document = repository.observeTranscript(recordingId)
        .filterNotNull()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Предпросмотр пересчитывается при смене формата и при правке расшифровки; старый запрос
    // отменяется, чтобы медленный ответ не перетёр свежий.
    private val preview: Flow<String?> = combine(options, document.filterNotNull()) { opts, doc -> opts to doc }
        .distinctUntilChanged()
        .mapLatest<Pair<ExportOptions, TranscriptDocument>, String?> { (opts, doc) ->
            if (doc.asrDraft != null) return@mapLatest ""
            try {
                gateway.preview(recordingId, opts)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ""
            }
        }
        .onStart { emit(null) }

    val state: StateFlow<ExportUiState> = combine(document, options, preview, busy) { doc, opts, text, isBusy ->
        stateOf(doc, opts, text, isBusy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExportUiState(options = options.value))

    private fun stateOf(doc: TranscriptDocument?, opts: ExportOptions, text: String?, isBusy: Boolean) =
        ExportUiState(
            title = doc?.title.orEmpty(),
            durationMs = doc?.durationMs ?: 0,
            speakerCount = doc?.speakers?.count { it.shareFraction > 0f } ?: 0,
            options = opts,
            preview = text,
            busy = isBusy,
            exportable = doc != null && doc.asrDraft == null,
        )

    fun onFormatChange(format: ExportFormat) = options.update { it.copy(format = format) }

    fun onSpeakerNamesChange(enabled: Boolean) = options.update { it.copy(useSpeakerNames = enabled) }

    fun onIncludeAudioChange(enabled: Boolean) = options.update { it.copy(includeAudio = enabled) }

    fun onAudioOnlyChange(enabled: Boolean) = options.update { it.copy(audioOnly = enabled) }

    fun suggestedFileName(): String = saveTarget().fileName

    fun mimeType(): String = saveTarget().mimeType

    private fun saveTarget() = gateway.saveTarget(recordingId, document.value?.title.orEmpty(), options.value)

    fun onShare() = runExclusive {
        val opts = options.value
        events.send(
            try {
                check(document.value?.asrDraft == null && document.value != null)
                ExportEvent.Share(gateway.prepareShare(recordingId, opts))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ExportEvent.ShareFailed
            },
        )
    }

    /** [documentUri] пришёл из CreateDocument; null означает, что пользователь закрыл выбор папки. */
    fun onSaveTarget(documentUri: String?) {
        if (documentUri == null) return
        runExclusive {
            val opts = options.value
            events.send(
                try {
                    check(document.value?.asrDraft == null && document.value != null)
                    val name = gateway.saveTo(documentUri, recordingId, opts)
                    ExportEvent.Saved(name, documentUri, gateway.saveTarget(recordingId, document.value?.title.orEmpty(), opts).mimeType)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ExportEvent.SaveFailed
                },
            )
        }
    }

    /** Повторное нажатие во время записи файла игнорируется, чтобы не открыть два окна. */
    private fun runExclusive(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                busy.value = false
            }
        }
    }
}
