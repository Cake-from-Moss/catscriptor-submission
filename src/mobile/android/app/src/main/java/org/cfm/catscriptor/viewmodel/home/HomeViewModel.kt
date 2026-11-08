package org.cfm.catscriptor.viewmodel.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.cfm.catscriptor.domain.app.ImportResult
import org.cfm.catscriptor.domain.app.ProcessingController
import org.cfm.catscriptor.domain.app.ProcessingStage
import org.cfm.catscriptor.domain.app.RecorderController
import org.cfm.catscriptor.domain.app.RecorderState
import org.cfm.catscriptor.domain.app.RecordingRepository
import org.cfm.catscriptor.domain.app.SettingsRepository
import org.cfm.catscriptor.domain.app.StartRecordingResult

/** Разовые события для Snackbar; тексты подставляет UI. */
sealed interface HomeEvent {
    data class LowStorage(val minutesAvailable: Int) : HomeEvent
    data object RecordingUnavailable : HomeEvent
    data object RecordingStopFailed : HomeEvent
    data object ImportUnsupported : HomeEvent
    data object ImportModelsUnavailable : HomeEvent

    /** Звонок или другое приложение забрали микрофон, запись на паузе. */
    data object RecordingInterrupted : HomeEvent
}

/**
 * Главный экран. Знает только интерфейсы из domain.app: склеивает их потоки в [HomeUiState]
 * и передаёт действия пользователя. [scope] подменяется в тестах; по умолчанию это главный поток.
 */
class HomeViewModel(
    recordings: RecordingRepository,
    private val processing: ProcessingController,
    private val recorder: RecorderController,
    private val settings: SettingsRepository,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope) {

    private val focus = MutableStateFlow(DetailFocus())
    private val eventChannel = Channel<HomeEvent>(Channel.BUFFERED)
    private var handledDetailsRequest = 0
    private var stoppingRecording = false
    private var suppressStopTransition = false

    val events: Flow<HomeEvent> = eventChannel.receiveAsFlow()

    // Уровень сигнала меняется 10 раз в секунду; для разметки экрана важны только эти поля.
    private val recorderShape = recorder.state
        .map { state ->
            (state as? RecorderState.Active)
                ?.copy(elapsedMs = 0, levelDbfs = 0f, peakDbfs = 0f, speechDetected = false)
                ?: state
        }
        .distinctUntilChanged()

    val uiState: StateFlow<HomeUiState> = combine(
        combine(recorderShape, settings.settings.map { it.liveTextEnabled }.distinctUntilChanged(), ::Pair),
        recordings.observe(),
        processing.observeJobs(),
        processing.modelsStatus,
        focus,
    ) { (recorderState, live), summaries, jobs, models, currentFocus ->
        buildHomeUiState(
            recorderAvailable = recorder.isAvailable,
            recorderState = recorderState,
            liveTextEnabled = live,
            summaries = summaries,
            jobs = jobs,
            modelsStatus = models,
            focus = currentFocus,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        HomeUiState(recorderAvailable = recorder.isAvailable, liveTextEnabled = settings.settings.value.liveTextEnabled),
    )

    val signal: StateFlow<RecorderSignal?> = recorder.state
        .map { it.toSignal() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), recorder.state.value.toSignal())

    init {
        viewModelScope.launch {
            var wasInterrupted = false
            var wasRecording = recorder.state.value is RecorderState.Active
            recorder.state.collect { state ->
                val recording = state is RecorderState.Active
                if (wasRecording && !recording) {
                    if (suppressStopTransition) suppressStopTransition = false
                    else eventChannel.send(HomeEvent.RecordingStopFailed)
                }
                wasRecording = recording
                val interrupted = (state as? RecorderState.Active)?.pausedByInterruption == true
                if (interrupted && !wasInterrupted) eventChannel.send(HomeEvent.RecordingInterrupted)
                wasInterrupted = interrupted
            }
        }
    }

    /** Вызывается после проверки разрешения на микрофон. */
    fun startRecording() {
        viewModelScope.launch {
            when (val result = recorder.start(liveText = settings.settings.value.liveTextEnabled)) {
                StartRecordingResult.Started -> Unit
                is StartRecordingResult.LowStorage -> eventChannel.send(HomeEvent.LowStorage(result.minutesAvailable))
                StartRecordingResult.Unavailable -> eventChannel.send(HomeEvent.RecordingUnavailable)
            }
        }
    }

    fun stopRecording() {
        viewModelScope.launch {
            if (stoppingRecording || recorder.state.value !is RecorderState.Active) return@launch
            stoppingRecording = true
            suppressStopTransition = true
            try {
                val recordingId = recorder.stop()
                if (recordingId != null) showDetails(recordingId)
                else eventChannel.send(HomeEvent.RecordingStopFailed)
            } finally {
                if (recorder.state.value is RecorderState.Active) suppressStopTransition = false
                stoppingRecording = false
            }
        }
    }

    fun pauseRecording() = recorder.pause()

    fun cancelRecording() {
        viewModelScope.launch {
            if (stoppingRecording || recorder.state.value !is RecorderState.Active) return@launch
            stoppingRecording = true
            suppressStopTransition = true
            try { recorder.cancel() }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                eventChannel.send(HomeEvent.RecordingStopFailed)
            } finally {
                if (recorder.state.value is RecorderState.Active) suppressStopTransition = false
                stoppingRecording = false
            }
        }
    }

    fun resumeRecording() = recorder.resume()

    fun addMark() = recorder.addMark()
    fun addNote(text: String, positionMs: Long? = null) = recorder.addNote(text, positionMs)
    fun deleteNote(noteId: String) = recorder.deleteNote(noteId)

    /** Live-текст можно переключить и во время записи. */
    fun toggleLiveText() {
        val enabled = !settings.settings.value.liveTextEnabled
        settings.update { it.copy(liveTextEnabled = enabled) }
        if (recorder.state.value is RecorderState.Active) recorder.setLiveText(enabled)
    }

    /** [documentUri] уже с правом на чтение; [displayName] становится названием записи. */
    fun importFile(documentUri: String, displayName: String) {
        viewModelScope.launch {
            when (val result = processing.importFile(documentUri, displayName)) {
                is ImportResult.Accepted -> showDetails(result.recordingId)
                ImportResult.UnsupportedFormat -> eventChannel.send(HomeEvent.ImportUnsupported)
                ImportResult.ModelsUnavailable -> eventChannel.send(HomeEvent.ImportModelsUnavailable)
            }
        }
    }

    fun showDetails(recordingId: String?) {
        focus.value = DetailFocus(expanded = true, recordingId = recordingId)
    }

    fun collapseDetails() = focus.update { it.copy(expanded = false) }

    /**
     * Запрос навигации раскрыть карточку. Счётчик сравнивается с уже обработанным, потому что
     * экран пересоздаётся при смене вкладки и иначе раскрывал бы карточку на каждом возврате.
     */
    fun onDetailsRequest(request: Int) {
        if (request > handledDetailsRequest) {
            handledDetailsRequest = request
            showDetails(null)
        }
    }

    fun cancelProcessing(recordingId: String) {
        collapseDetails()
        viewModelScope.launch { processing.cancel(recordingId) }
    }

    fun retryStage(recordingId: String) {
        viewModelScope.launch { processing.retry(recordingId) }
    }

    fun skipStage(recordingId: String, stage: ProcessingStage) {
        viewModelScope.launch { processing.skipStage(recordingId, stage) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
