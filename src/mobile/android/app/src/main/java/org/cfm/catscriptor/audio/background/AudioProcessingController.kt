package org.cfm.catscriptor.audio.background

import org.cfm.catscriptor.domain.app.CorrectionLevel
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.cfm.catscriptor.domain.pipeline.AudioInput

enum class AudioProcessingPhase { IDLE, RUNNING, COMPLETED, CANCELLED, FAILED }

data class AudioProcessingState(
    val phase: AudioProcessingPhase = AudioProcessingPhase.IDLE,
    val sampleCount: Long = 0,
    val message: String? = null,
    val stage: org.cfm.catscriptor.domain.app.ProcessingStage = org.cfm.catscriptor.domain.app.ProcessingStage.PREPARE_AUDIO,
    val stages: List<org.cfm.catscriptor.domain.app.StageProgress> = emptyList(),
    val partialText: String = "",
    val asrDraft: List<org.cfm.catscriptor.domain.pipeline.AsrSegment>? = null,
    /** Реплики со спикерами после диаризации; null — диаризация не выполнялась. Это черновик до LLM (#7). */
    val speakerDraft: List<org.cfm.catscriptor.domain.pipeline.Utterance>? = null,
    /** Финальный транскрипт после локальной LLM-коррекции (#7); null — коррекция ещё не выполнена. */
    val transcript: List<org.cfm.catscriptor.domain.pipeline.Utterance>? = null,
)

object AudioProcessingController {
    private val mutableState = MutableStateFlow(AudioProcessingState())
    val state: StateFlow<AudioProcessingState> = mutableState.asStateFlow()

    /**
     * Повторный запуск до завершения очистки текущей задачи игнорируется.
     * [speakerCount] — число спикеров из настроек на момент запуска; null — автоопределение.
     */
    @Synchronized
    fun start(context: Context, input: AudioInput, speakerCount: Int? = null, correctionLevel: CorrectionLevel = CorrectionLevel.SMART) {
        if (mutableState.value.phase == AudioProcessingPhase.RUNNING) return
        publish(AudioProcessingState(AudioProcessingPhase.RUNNING, message = "Подготовка аудио…"))
        try {
            // Временный SAF-доступ действует в рамках задачи приложения; сервис не восстанавливается.
            context.startForegroundService(Intent(context, AudioProcessingService::class.java).apply {
                action = AudioProcessingService.ACTION_START
                data = Uri.parse(input.documentId)
                putExtra(AudioProcessingService.EXTRA_CORRECTION_LEVEL, correctionLevel.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (speakerCount != null) putExtra(AudioProcessingService.EXTRA_SPEAKER_COUNT, speakerCount)
            })
        } catch (error: Exception) {
            logProcessingError("Не удалось запустить foreground service", error)
            publish(AudioProcessingState(AudioProcessingPhase.FAILED, message = "Не удалось запустить подготовку аудио"))
        }
    }

    fun cancel(context: Context) {
        if (state.value.phase != AudioProcessingPhase.RUNNING) return
        try {
            context.startService(Intent(context, AudioProcessingService::class.java).apply {
                action = AudioProcessingService.ACTION_CANCEL
            })
        } catch (error: Exception) {
            logProcessingError("Не удалось доставить отмену сервису", error)
            // Не объявляем отмену завершённой, пока сервис ещё может владеть PCM.
        }
    }

    /** Текст остаётся внутри процесса: большие транскрипты не передаются через Binder. */
    @Synchronized
    internal fun startCorrection(context: Context, seed: AudioProcessingState, level: CorrectionLevel = CorrectionLevel.SMART) {
        check(mutableState.value.phase != AudioProcessingPhase.RUNNING) { "Дождитесь завершения текущей обработки" }
        publish(seed.copy(phase = AudioProcessingPhase.RUNNING,
            stage = org.cfm.catscriptor.domain.app.ProcessingStage.CORRECT_TEXT))
        try {
            context.startForegroundService(Intent(context, AudioProcessingService::class.java)
                .setAction(AudioProcessingService.ACTION_CORRECT_TEXT)
                .putExtra(AudioProcessingService.EXTRA_CORRECTION_LEVEL, level.name))
        } catch (error: Exception) {
            logProcessingError("Не удалось запустить коррекцию", error)
            publish(seed.copy(phase = AudioProcessingPhase.FAILED, message = "Не удалось запустить пунктуацию и правку"))
        }
    }

    internal fun publish(value: AudioProcessingState) { mutableState.value = value }
}
