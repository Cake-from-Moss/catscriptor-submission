package org.cfm.catscriptor.audio.background

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.cfm.catscriptor.domain.app.ProcessingStage
import org.cfm.catscriptor.domain.app.StageProgress
import org.cfm.catscriptor.domain.app.StageStatus
import org.cfm.catscriptor.domain.diarization.SpeakerTurnPlanner
import org.cfm.catscriptor.domain.pipeline.*

/**
 * Рабочий путь #6 + #7: PCM → VAD → диаризация → ASR по ходам спикеров → слияние в реплики
 * → коррекция локальной LLM.
 *
 * Модели загружаются строго по одной и закрываются сразу после своего этапа: VAD освобождается до
 * создания диаризации, диаризация — до создания GigaAM, GigaAM — до загрузки LLM. PCM закрывается
 * после ASR (последнего аудиоэтапа). Результат — финальный транскрипт: спикеры и таймкоды
 * исходные, текст исправлен LLM; пустой ответ LLM не стирает распознанный текст.
 */
internal class DiarizedDraftRunner(
    private val decoder: AudioDecoder,
    private val vadFactory: () -> VoiceActivityDetector,
    private val diarizerFactory: () -> DiarizationBackend,
    private val asrFactory: () -> TranscriptionBackend,
    private val merger: UtteranceMerger,
    private val correctorFactory: () -> TextCorrectionBackend,
    private val clock: () -> Long,
    private val planner: SpeakerTurnPlanner = SpeakerTurnPlanner(),
    /** Тайминги этапов на устройстве; в JVM-тестах Android-лог недоступен — no-op. */
    private val logTiming: (String) -> Unit = {},
    private val textCorrectionEnabled: Boolean = true,
) {
    suspend fun run(input: AudioInput, publish: (AudioProcessingState) -> Unit): AudioProcessingState {
        var state = AudioProcessingState(AudioProcessingPhase.RUNNING)
        val stages = ProcessingStage.entries.associateWith { StageStatus.Pending as StageStatus }.toMutableMap()
        var started = clock()
        fun snapshot() = stages.map { StageProgress(it.key, it.value) }
        fun emit(stage: ProcessingStage, processedMs: Long? = null) {
            stages[stage] = StageStatus.Running((clock() - started).coerceAtLeast(0), processedMs)
            state = state.copy(stage = stage, stages = snapshot())
            publish(state)
        }
        fun finish(stage: ProcessingStage) {
            stages[stage] = StageStatus.Done((clock() - started).coerceAtLeast(0))
            // Тайминги этапов на устройстве: без них невозможно отличить медленную модель
            // от зависшего этапа при разборе logcat.
            logTiming("$stage done in ${clock() - started} ms")
            started = clock()
        }
        try {
            emit(ProcessingStage.PREPARE_AUDIO)
            decoder.decode(input).use { pcm ->
                state = state.copy(sampleCount = pcm.sampleCount)
                finish(ProcessingStage.PREPARE_AUDIO)

                emit(ProcessingStage.DETECT_SPEECH)
                val speech = vadFactory().use { it.detect(pcm) }
                currentCoroutineContext().ensureActive()
                finish(ProcessingStage.DETECT_SPEECH)

                var turns = emptyList<SpeakerSegment>()
                emit(ProcessingStage.DIARIZE)
                if (speech.isNotEmpty()) {
                    val speakers = diarizerFactory().use { it.diarize(pcm, speech) }
                    currentCoroutineContext().ensureActive()
                    turns = planner.plan(speakers, speech)
                }
                finish(ProcessingStage.DIARIZE)

                emit(ProcessingStage.TRANSCRIBE, 0)
                val recognized = mutableListOf<AsrSegment>()
                val partial = StringBuilder()
                if (turns.isNotEmpty()) asrFactory().use { backend ->
                    for (turn in turns) {
                        currentCoroutineContext().ensureActive()
                        val fragment = backend.transcribe(pcm, SpeechSegment(turn.time))
                        recognized += fragment
                        if (fragment.text.isNotBlank()) {
                            if (partial.isNotEmpty()) partial.append('\n')
                            partial.append(fragment.text)
                        }
                        state = state.copy(partialText = partial.toString())
                        emit(ProcessingStage.TRANSCRIBE, turn.time.endMs)
                    }
                }
                currentCoroutineContext().ensureActive()
                // Сопоставление идёт по ходам: время ASR-фрагмента совпадает с интервалом хода его владельца.
                val transcript = merger.merge(recognized, turns)
                finish(ProcessingStage.TRANSCRIBE)

                // Реплики со спикерами публикуются до LLM: если коррекция упадёт, пользователь может
                // сохранить запись без неё («Пропустить этап»), не повторяя VAD, диаризацию и ASR.
                state = state.copy(
                    asrDraft = transcript.transcript.map { AsrSegment(TimeRange(it.startMs, it.endMs), it.text) },
                    speakerDraft = transcript.transcript,
                )
            }
            // Аудиокэш освобождён до загрузки LLM, спикеры и интервалы уже сохранены в черновике.
            val draft = checkNotNull(state.speakerDraft)
            if (!textCorrectionEnabled) {
                currentCoroutineContext().ensureActive()
                stages[ProcessingStage.CORRECT_TEXT] = StageStatus.Skipped
                return state.copy(
                    phase = AudioProcessingPhase.COMPLETED,
                    stage = ProcessingStage.CORRECT_TEXT,
                    transcript = draft,
                    stages = snapshot(),
                )
            }

            // Финал: локальная LLM правит только текст, спикеров и таймкоды не трогает (#7).
            // processedMs = 0, как у ASR: иначе прогресс этапа считался бы половиной веса,
            // а после первой реплики откатывался назад.
            emit(ProcessingStage.CORRECT_TEXT, 0)
            val corrected = ArrayList<Utterance>(draft.size)
            if (draft.isNotEmpty()) {
                correctorFactory().use { corrector ->
                    for (utterance in draft) {
                        currentCoroutineContext().ensureActive()
                        val text = corrector.correct(utterance)
                        // Пустой ответ LLM не должен стирать распознанный текст.
                        corrected += if (text.isBlank()) utterance else utterance.copy(text = text)
                        emit(ProcessingStage.CORRECT_TEXT, utterance.endMs)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            finish(ProcessingStage.CORRECT_TEXT)

            state = state.copy(
                phase = AudioProcessingPhase.COMPLETED,
                transcript = corrected,
                stages = snapshot(),
            )
            return state
        } catch (error: Exception) {
            // Текст исключения может содержать URI документа (см. AudioProcessingDiagnostics): в UI не отдаём.
            stages[state.stage] = StageStatus.Failed(null)
            publish(state.copy(stages = snapshot()))
            throw error
        }
    }
}
