package org.cfm.catscriptor.viewmodel.home

import org.cfm.catscriptor.domain.app.LiveLine
import org.cfm.catscriptor.domain.app.ModelsStatus
import org.cfm.catscriptor.domain.app.ProcessingJob
import org.cfm.catscriptor.domain.app.RecorderState
import org.cfm.catscriptor.domain.app.RecordingStatus
import org.cfm.catscriptor.domain.app.RecordingSummary

/** Сколько записей показывает блок «Недавние». */
const val RECENTS_LIMIT = 3

/**
 * Состояние главного экрана без быстро меняющихся величин: уровень сигнала и таймер
 * идут отдельным потоком [RecorderSignal], чтобы не пересобирать весь экран 10 раз в секунду.
 */
data class HomeUiState(
    val recorderAvailable: Boolean = false,
    val liveTextEnabled: Boolean = true,
    /** null, пока запись не идёт. */
    val recording: RecordingUi? = null,
    /** Есть задачи обработки: новая запись встанет в очередь. */
    val queueBusy: Boolean = false,
    /** null, если модели на месте. */
    val modelsIssue: ModelsIssue? = null,
    val content: HomeContent = HomeContent.Overview(),
)

data class RecordingUi(val paused: Boolean, val notes: List<org.cfm.catscriptor.domain.app.RecordingNote> = emptyList())

data class ModelsIssue(val details: String?)

/** Что показываем под рядом кнопок. */
sealed interface HomeContent {
    /** Ожидание: компактные карточки «В обработке», «Недавние» или пустое состояние. */
    data class Overview(
        val jobs: List<ProcessingJob> = emptyList(),
        val recents: List<RecordingSummary> = emptyList(),
        val showEmptyState: Boolean = false,
        val showModelsIssue: Boolean = false,
    ) : HomeContent

    /** После «Стоп» или импорта: этапы обработки и «Уже распознано». */
    data class ProcessingDetail(val job: ProcessingJob) : HomeContent

    data class LiveFeed(val lines: List<LiveLine>, val status: String = "", val recognizing: Boolean = false) : HomeContent

    data class RecordingWithoutLive(val marksMs: List<Long>) : HomeContent
}

/** Быстро меняющиеся величины записи для таймера, волны и индикатора уровня. */
data class RecorderSignal(
    val elapsedMs: Long,
    val paused: Boolean,
    val levelDbfs: Float,
    val peakDbfs: Float,
    val speechDetected: Boolean,
)

/**
 * Какую задачу раскрыть в подробной карточке. [recordingId] = null означает «активную задачу»,
 * то есть первую в очереди.
 */
data class DetailFocus(val expanded: Boolean = false, val recordingId: String? = null)

/** Чистая сборка состояния экрана из снимков портов; ViewModel только подаёт сюда потоки. */
fun buildHomeUiState(
    recorderAvailable: Boolean,
    recorderState: RecorderState,
    liveTextEnabled: Boolean,
    summaries: List<RecordingSummary>,
    jobs: List<ProcessingJob>,
    modelsStatus: ModelsStatus,
    focus: DetailFocus,
): HomeUiState {
    val active = recorderState as? RecorderState.Active
    val modelsIssue = (modelsStatus as? ModelsStatus.Unavailable)?.let { ModelsIssue(it.details) }
    val focusedJob = if (focus.expanded) {
        // Если задача с этим id уже завершилась или отменена, карточку не подменяем другой задачей.
        if (focus.recordingId == null) jobs.firstOrNull() else jobs.firstOrNull { it.recordingId == focus.recordingId }
    } else {
        null
    }
    val content = when {
        active != null && liveTextEnabled -> HomeContent.LiveFeed(active.liveLines, active.liveStatus, active.liveRecognizing)
        active != null -> HomeContent.RecordingWithoutLive(active.marksMs)
        focusedJob != null -> HomeContent.ProcessingDetail(focusedJob)
        else -> {
            // Записи в обработке уже видны в карточках «В обработке», в «Недавних» только готовые.
            val recents = summaries
                .filter { it.status == RecordingStatus.Ready || it.status == RecordingStatus.AsrReady }
                .sortedByDescending { it.createdAtEpochMs }
                .take(RECENTS_LIMIT)
            HomeContent.Overview(
                jobs = jobs,
                recents = recents,
                // Если в библиотеке только записи без задачи (например, с ошибкой), показывать нечего:
                // без пустого состояния экран под «пилюлей» остался бы совсем пустым.
                showEmptyState = recents.isEmpty() && jobs.isEmpty(),
                showModelsIssue = modelsIssue != null,
            )
        }
    }
    return HomeUiState(
        recorderAvailable = recorderAvailable,
        liveTextEnabled = liveTextEnabled,
        recording = active?.let { RecordingUi(paused = it.paused, notes = it.notes) },
        queueBusy = jobs.isNotEmpty(),
        modelsIssue = modelsIssue,
        content = content,
    )
}

fun RecorderState.toSignal(): RecorderSignal? = (this as? RecorderState.Active)?.let {
    RecorderSignal(
        elapsedMs = it.elapsedMs,
        paused = it.paused,
        levelDbfs = it.levelDbfs,
        peakDbfs = it.peakDbfs,
        speechDetected = it.speechDetected,
    )
}
