package org.cfm.catscriptor.audio.background

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.cfm.catscriptor.domain.app.ProcessingStage
import org.cfm.catscriptor.domain.app.StageProgress
import org.cfm.catscriptor.domain.app.StageStatus
import org.cfm.catscriptor.domain.pipeline.TextCorrectionBackend
import org.cfm.catscriptor.domain.pipeline.Utterance

/** Повторная правка уже готовых реплик: аудио, интервалы и спикеры не пересчитываются. */
internal class TextCorrectionRunner(
    private val correctorFactory: () -> TextCorrectionBackend,
    private val clock: () -> Long,
) {
    suspend fun run(seed: AudioProcessingState, publish: (AudioProcessingState) -> Unit): AudioProcessingState {
        val source = requireNotNull(seed.transcript)
        val started = clock()
        fun progress(status: StageStatus) = seed.copy(
            phase = AudioProcessingPhase.RUNNING,
            stage = ProcessingStage.CORRECT_TEXT,
            stages = listOf(StageProgress(ProcessingStage.CORRECT_TEXT, status)),
        )
        publish(progress(StageStatus.Running(0, 0)))
        val corrected = ArrayList<Utterance>(source.size)
        if (source.isNotEmpty()) correctorFactory().use { corrector ->
            for (utterance in source) {
                currentCoroutineContext().ensureActive()
                val text = corrector.correct(utterance)
                corrected += if (text.isBlank()) utterance else utterance.copy(text = text)
                publish(progress(StageStatus.Running(clock() - started, utterance.endMs)))
            }
        }
        currentCoroutineContext().ensureActive()
        return progress(StageStatus.Done(clock() - started)).copy(
            phase = AudioProcessingPhase.COMPLETED, transcript = corrected,
        )
    }
}
