package org.cfm.catscriptor.domain.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Порядок совпадает с конвейером из AGENTS.md. */
/** Порядок совпадает с порядком выполнения: диаризация предшествует ASR по ходам спикеров (#6). */
enum class ProcessingStage { PREPARE_AUDIO, DETECT_SPEECH, DIARIZE, TRANSCRIBE, CORRECT_TEXT }

sealed interface StageStatus {
    data object Pending : StageStatus

    /** [processedMs] заполняется только для этапа распознавания: «8:10 из 12:41». */
    data class Running(val elapsedMs: Long, val processedMs: Long? = null) : StageStatus
    data class Done(val elapsedMs: Long) : StageStatus
    data class Failed(val message: String?) : StageStatus
    data object Skipped : StageStatus
}

data class StageProgress(val stage: ProcessingStage, val status: StageStatus)

/**
 * Снимок обработки одной записи. Должен приходить из Foreground Service (#8).
 * [partialText] — уже распознанный текст для блока «Уже распознано».
 */
data class ProcessingJob(
    val recordingId: String,
    val title: String,
    val source: RecordingSource,
    val fileExtension: String?,
    val durationMs: Long,
    val overallFraction: Float,
    val stages: List<StageProgress>,
    val remainingMs: Long?,
    val partialText: String,
)

sealed interface ModelsStatus {
    data object Ready : ModelsStatus

    /** Модели отсутствуют или повреждены: запись разрешена, обработка нет. */
    data class Unavailable(val details: String?) : ModelsStatus
}

sealed interface ImportResult {
    data class Accepted(val recordingId: String) : ImportResult
    data object UnsupportedFormat : ImportResult
    data object ModelsUnavailable : ImportResult
}

/** Ход повторной правки текста готовой записи; [fraction] — доля обработанного аудио от 0 до 1. */
data class CorrectionProgress(val recordingId: String, val title: String, val fraction: Float)

/** Очередь обработки. Реальная реализация запускает Foreground Service и пайплайн. */
interface ProcessingController {
    val modelsStatus: StateFlow<ModelsStatus>

    /** Идущая сейчас правка текста готовой записи; null, если её нет. */
    val correction: StateFlow<CorrectionProgress?>

    /** Задачи в порядке очереди; активная идёт первой. */
    fun observeJobs(): Flow<List<ProcessingJob>>

    /** [documentUri] — URI из SAF; разрешение на чтение уже получено вызывающим кодом. */
    suspend fun importFile(documentUri: String, displayName: String): ImportResult
    suspend fun cancel(recordingId: String)
    suspend fun retry(recordingId: String)

    /**
     * Только локальная пунктуация и правка сохранённого текста, без повторного ASR.
     * [level] действует лишь на этот запуск и не меняет настройки; допустимо повторять
     * на уже исправленной записи. [CorrectionLevel.OFF] означает «умная».
     */
    suspend fun correctText(recordingId: String, level: CorrectionLevel)

    /** «Сохранить без этого этапа», например без пунктуации. */
    suspend fun skipStage(recordingId: String, stage: ProcessingStage)
}
