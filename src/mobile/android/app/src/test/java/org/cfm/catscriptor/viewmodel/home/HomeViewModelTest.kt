package org.cfm.catscriptor.viewmodel.home

import org.cfm.catscriptor.domain.app.CorrectionLevel
import org.cfm.catscriptor.domain.app.CorrectionProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.cfm.catscriptor.app.InMemorySettingsRepository
import org.cfm.catscriptor.domain.app.AppSettings
import org.cfm.catscriptor.domain.app.ImportResult
import org.cfm.catscriptor.domain.app.LiveLine
import org.cfm.catscriptor.domain.app.ModelsStatus
import org.cfm.catscriptor.domain.app.ProcessingController
import org.cfm.catscriptor.domain.app.ProcessingJob
import org.cfm.catscriptor.domain.app.ProcessingStage
import org.cfm.catscriptor.domain.app.RecorderController
import org.cfm.catscriptor.domain.app.RecorderState
import org.cfm.catscriptor.domain.app.RecordingQuery
import org.cfm.catscriptor.domain.app.RecordingRepository
import org.cfm.catscriptor.domain.app.RecordingSource
import org.cfm.catscriptor.domain.app.RecordingStatus
import org.cfm.catscriptor.domain.app.RecordingSummary
import org.cfm.catscriptor.domain.app.StageProgress
import org.cfm.catscriptor.domain.app.StageStatus
import org.cfm.catscriptor.domain.app.StartRecordingResult
import org.cfm.catscriptor.domain.app.TranscriptDocument
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildHomeUiStateTest {
    @Test fun notesAreAvailableWithLiveEnabledAndDisabled() {
        val notes = listOf(org.cfm.catscriptor.domain.app.RecordingNote("note", 100, "Важно"))
        val active = RecorderState.Active(200, false, -30f, -20f, true,
            listOf(100), emptyList(), notes = notes)
        assertEquals(notes, build(recorderState = active, live = true).recording?.notes)
        assertEquals(notes, build(recorderState = active, live = false).recording?.notes)
    }

    private fun build(
        recorderState: RecorderState = RecorderState.Idle,
        live: Boolean = true,
        summaries: List<RecordingSummary> = emptyList(),
        jobs: List<ProcessingJob> = emptyList(),
        models: ModelsStatus = ModelsStatus.Ready,
        focus: DetailFocus = DetailFocus(),
    ) = buildHomeUiState(true, recorderState, live, summaries, jobs, models, focus)

    @Test
    fun `no recordings and no jobs shows the empty state`() {
        val content = build().content as HomeContent.Overview
        assertTrue(content.showEmptyState)
        assertTrue(content.recents.isEmpty())
    }

    @Test
    fun `only unfinished recordings without jobs still show the empty state`() {
        val summaries = listOf(summary("f", status = RecordingStatus.Failed(ProcessingStage.DIARIZE)))
        val content = build(summaries = summaries).content as HomeContent.Overview
        assertTrue(content.showEmptyState)
    }

    @Test
    fun `recents are the three newest ready recordings`() {
        val summaries = listOf(
            summary("a", createdAt = 1),
            summary("b", createdAt = 4),
            summary("c", createdAt = 3, status = RecordingStatus.Processing(0.5f, ProcessingStage.TRANSCRIBE)),
            summary("d", createdAt = 2),
            summary("e", createdAt = 5),
        )
        val content = build(summaries = summaries).content as HomeContent.Overview
        assertEquals(listOf("e", "b", "d"), content.recents.map { it.id })
        assertEquals(false, content.showEmptyState)
    }

    @Test
    fun `jobs show compact cards and make the pill say the recording will be queued`() {
        val state = build(summaries = listOf(summary("x")), jobs = listOf(job("j1")))
        assertTrue(state.queueBusy)
        assertEquals(listOf("j1"), (state.content as HomeContent.Overview).jobs.map { it.recordingId })
    }

    @Test
    fun `expanded focus shows the focused job, or the active one when no id is given`() {
        val jobs = listOf(job("j1"), job("j2"))
        val focused = build(jobs = jobs, focus = DetailFocus(expanded = true, recordingId = "j2")).content
        assertEquals("j2", (focused as HomeContent.ProcessingDetail).job.recordingId)
        val active = build(jobs = jobs, focus = DetailFocus(expanded = true, recordingId = null)).content
        assertEquals("j1", (active as HomeContent.ProcessingDetail).job.recordingId)
    }

    @Test
    fun `finished focused job falls back to the overview instead of another job`() {
        val content = build(jobs = listOf(job("j1")), focus = DetailFocus(expanded = true, recordingId = "gone")).content
        assertTrue(content is HomeContent.Overview)
    }

    @Test
    fun `recording wins over processing details and follows the live switch`() {
        val line = LiveLine("l1", "speaker_1", 0, "текст", isFinal = false)
        val active = active(liveLines = listOf(line), marks = listOf(1_000L))
        val focus = DetailFocus(expanded = true)
        val live = build(recorderState = active, live = true, jobs = listOf(job("j1")), focus = focus)
        assertEquals(HomeContent.LiveFeed(listOf(line)), live.content)
        assertEquals(RecordingUi(paused = false), live.recording)
        val noLive = build(recorderState = active, live = false, jobs = listOf(job("j1")), focus = focus)
        assertEquals(HomeContent.RecordingWithoutLive(listOf(1_000L)), noLive.content)
    }

    @Test
    fun `unavailable models produce an issue card with details`() {
        val state = build(models = ModelsStatus.Unavailable("нет файлов"))
        assertEquals(ModelsIssue("нет файлов"), state.modelsIssue)
        assertTrue((state.content as HomeContent.Overview).showModelsIssue)
    }
}

class HomeViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val recordings = FakeRecordings()
    private val processing = FakeProcessing()
    private val recorder = FakeRecorder()
    private val settings = InMemorySettingsRepository(AppSettings(liveTextEnabled = true))
    private val events = mutableListOf<HomeEvent>()

    private val viewModel = HomeViewModel(recordings, processing, recorder, settings, scope).also { vm ->
        scope.launch { vm.uiState.collect { } }
        scope.launch { vm.events.collect { events += it } }
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `stop opens the details of the new recording`() {
        recorder.state.value = active()
        recorder.stopResult = "rec-1"
        processing.jobs.value = listOf(job("other"))
        viewModel.stopRecording()
        recorder.state.value = RecorderState.Idle
        processing.jobs.value = listOf(job("other"), job("rec-1"))
        assertEquals("rec-1", (viewModel.uiState.value.content as HomeContent.ProcessingDetail).job.recordingId)

        viewModel.collapseDetails()
        assertTrue(viewModel.uiState.value.content is HomeContent.Overview)
        viewModel.showDetails("rec-1")
        assertTrue(viewModel.uiState.value.content is HomeContent.ProcessingDetail)
    }

    @Test
    fun `stop without a recording reports failure but repeated idle stop is silent`() {
        recorder.state.value = active()
        viewModel.stopRecording()
        assertEquals(listOf(HomeEvent.RecordingStopFailed), events)
        viewModel.stopRecording()
        assertEquals(listOf(HomeEvent.RecordingStopFailed), events)
    }

    @Test
    fun `unexpected active to idle transition reports recording failure`() {
        recorder.state.value = active()
        recorder.state.value = RecorderState.Idle
        assertEquals(listOf(HomeEvent.RecordingStopFailed), events)
    }

    @Test
    fun `import results map to details or snackbar events`() {
        processing.jobs.value = listOf(job("imp"))
        processing.importResult = ImportResult.Accepted("imp")
        viewModel.importFile("content://x", "Интервью.m4a")
        assertEquals(listOf("content://x" to "Интервью.m4a"), processing.imports)
        assertTrue(viewModel.uiState.value.content is HomeContent.ProcessingDetail)

        processing.importResult = ImportResult.UnsupportedFormat
        viewModel.importFile("content://y", "y.txt")
        processing.importResult = ImportResult.ModelsUnavailable
        viewModel.importFile("content://z", "z.mp3")
        assertEquals(listOf(HomeEvent.ImportUnsupported, HomeEvent.ImportModelsUnavailable), events)
    }

    @Test
    fun `details request is handled once per increment`() {
        processing.jobs.value = listOf(job("j1"))
        viewModel.onDetailsRequest(1)
        assertTrue(viewModel.uiState.value.content is HomeContent.ProcessingDetail)
        viewModel.collapseDetails()
        // Возврат на вкладку повторяет тот же номер запроса: карточка не должна раскрыться снова.
        viewModel.onDetailsRequest(1)
        assertTrue(viewModel.uiState.value.content is HomeContent.Overview)
        viewModel.onDetailsRequest(2)
        assertTrue(viewModel.uiState.value.content is HomeContent.ProcessingDetail)
    }

    @Test
    fun `cancel collapses the details and forwards to processing`() {
        processing.jobs.value = listOf(job("j1"))
        viewModel.showDetails("j1")
        viewModel.cancelProcessing("j1")
        assertEquals(listOf("j1"), processing.cancelled)
        assertTrue(viewModel.uiState.value.content is HomeContent.Overview)
    }

    @Test
    fun `low storage and unavailable recorder become events`() {
        recorder.startResult = StartRecordingResult.LowStorage(7)
        viewModel.startRecording()
        recorder.startResult = StartRecordingResult.Unavailable
        viewModel.startRecording()
        assertEquals(listOf(HomeEvent.LowStorage(7), HomeEvent.RecordingUnavailable), events)
        assertEquals(listOf(true, true), recorder.startedWithLive)
    }

    @Test
    fun `interruption is reported once per pause`() {
        recorder.state.value = active()
        recorder.state.value = active(elapsed = 100, paused = true, interrupted = true)
        recorder.state.value = active(elapsed = 200, paused = true, interrupted = true)
        recorder.state.value = active(elapsed = 300)
        recorder.state.value = active(elapsed = 400, paused = true, interrupted = true)
        assertEquals(listOf(HomeEvent.RecordingInterrupted, HomeEvent.RecordingInterrupted), events)
    }

    @Test
    fun `live text toggles settings and the active recorder`() {
        viewModel.toggleLiveText()
        assertEquals(false, settings.settings.value.liveTextEnabled)
        assertTrue(recorder.liveChanges.isEmpty())

        recorder.state.value = active()
        viewModel.toggleLiveText()
        assertEquals(true, settings.settings.value.liveTextEnabled)
        assertEquals(listOf(true), recorder.liveChanges)
        assertTrue(viewModel.uiState.value.content is HomeContent.LiveFeed)
    }

    @Test
    fun `signal carries level and timer separately from the screen state`() {
        scope.launch { viewModel.signal.collect { } }
        recorder.state.value = active(elapsed = 4_000, level = -20f)
        val signal = viewModel.signal.value
        assertEquals(RecorderSignal(4_000, false, -20f, -14f, true), signal)
        recorder.state.value = RecorderState.Idle
        assertNull(viewModel.signal.value)
    }
}

private fun summary(
    id: String,
    createdAt: Long = 0,
    status: RecordingStatus = RecordingStatus.Ready,
) = RecordingSummary(
    id = id,
    title = id,
    source = RecordingSource.MICROPHONE,
    createdAtEpochMs = createdAt,
    durationMs = 60_000,
    speakerCount = 2,
    previewSpeakerId = "speaker_1",
    previewSpeakerName = null,
    previewText = "текст",
    status = status,
)

private fun job(id: String) = ProcessingJob(
    recordingId = id,
    title = id,
    source = RecordingSource.FILE,
    fileExtension = "m4a",
    durationMs = 60_000,
    overallFraction = 0.5f,
    stages = ProcessingStage.entries.map { StageProgress(it, StageStatus.Pending) },
    remainingMs = 30_000,
    partialText = "",
)

private fun active(
    elapsed: Long = 0,
    paused: Boolean = false,
    interrupted: Boolean = false,
    level: Float = -30f,
    liveLines: List<LiveLine> = emptyList(),
    marks: List<Long> = emptyList(),
) = RecorderState.Active(
    elapsedMs = elapsed,
    paused = paused,
    levelDbfs = level,
    peakDbfs = level + 6f,
    speechDetected = true,
    marksMs = marks,
    liveLines = liveLines,
    pausedByInterruption = interrupted,
)

private class FakeRecordings : RecordingRepository {
    val summaries = MutableStateFlow<List<RecordingSummary>>(emptyList())
    override fun observe(query: RecordingQuery): Flow<List<RecordingSummary>> = summaries
    override fun observeTranscript(recordingId: String): Flow<TranscriptDocument?> = MutableStateFlow(null)
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

private class FakeProcessing : ProcessingController {
    val jobs = MutableStateFlow<List<ProcessingJob>>(emptyList())
    var importResult: ImportResult = ImportResult.UnsupportedFormat
    val imports = mutableListOf<Pair<String, String>>()
    val cancelled = mutableListOf<String>()
    override val modelsStatus: StateFlow<ModelsStatus> = MutableStateFlow(ModelsStatus.Ready)
    override val correction: StateFlow<CorrectionProgress?> = MutableStateFlow(null)
    override fun observeJobs(): Flow<List<ProcessingJob>> = jobs
    override suspend fun importFile(documentUri: String, displayName: String): ImportResult {
        imports += documentUri to displayName
        return importResult
    }
    override suspend fun cancel(recordingId: String) {
        cancelled += recordingId
    }
    override suspend fun retry(recordingId: String) = Unit
    override suspend fun correctText(recordingId: String, level: CorrectionLevel) = Unit
    override suspend fun skipStage(recordingId: String, stage: ProcessingStage) = Unit
}

private class FakeRecorder : RecorderController {
    override suspend fun cancel() { state.value = RecorderState.Idle }
    override val isAvailable = true
    override val state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    var startResult: StartRecordingResult = StartRecordingResult.Started
    var stopResult: String? = null
    val startedWithLive = mutableListOf<Boolean>()
    val liveChanges = mutableListOf<Boolean>()
    override suspend fun start(liveText: Boolean): StartRecordingResult {
        startedWithLive += liveText
        return startResult
    }
    override suspend fun stop(): String? {
        state.value = RecorderState.Idle
        return stopResult
    }
    override fun pause() = Unit
    override fun resume() = Unit
    override fun setLiveText(enabled: Boolean) {
        liveChanges += enabled
    }
    override fun addMark() = Unit
    override fun addNote(text: String, positionMs: Long?) = Unit
    override fun deleteNote(noteId: String) = Unit
}
