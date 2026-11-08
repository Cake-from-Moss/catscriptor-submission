package org.cfm.catscriptor.domain.app

/** Этап можно пропустить после его ошибки: без LLM-коррекции остаётся распознанный текст со спикерами. */
val ProcessingStage.isOptional: Boolean get() = this == ProcessingStage.CORRECT_TEXT

/**
 * Оценка общего прогресса и оставшегося времени по снимку этапов.
 *
 * Этапы длятся очень по-разному, поэтому у каждого свой вес — доля времени из замера на телефоне
 * (vivo V2538, Snapdragon 4 Gen 2, docs/llm-correction.md): LLM ≈ 80 %, ASR ≈ 11 %, диаризация ≈ 7 %.
 * Внутри ASR и LLM доля берётся по обработанному аудио ([StageStatus.Running.processedMs]),
 * у остальных этапов — половина веса, пока этап идёт.
 */
object ProcessingProgress {
    private val weights = mapOf(
        ProcessingStage.PREPARE_AUDIO to 0.01f,
        ProcessingStage.DETECT_SPEECH to 0.01f,
        ProcessingStage.DIARIZE to 0.07f,
        ProcessingStage.TRANSCRIBE to 0.11f,
        ProcessingStage.CORRECT_TEXT to 0.80f,
    )

    /** Раньше этой доли и этого времени экстраполяция слишком шумная — оценка не показывается. */
    private const val MIN_FRACTION_FOR_ESTIMATE = 0.05f
    private const val MIN_ELAPSED_FOR_ESTIMATE_MS = 3_000L

    /** Доля 0..1; этапы без статуса считаются ожидающими. */
    fun fraction(stages: List<StageProgress>, durationMs: Long): Float {
        val byStage = stages.associate { it.stage to it.status }
        var total = 0f
        for (stage in ProcessingStage.entries) {
            val weight = weights.getValue(stage)
            total += weight * when (val status = byStage[stage]) {
                is StageStatus.Done, StageStatus.Skipped -> 1f
                is StageStatus.Running -> status.processedMs
                    ?.takeIf { durationMs > 0 }
                    ?.let { (it.toFloat() / durationMs).coerceIn(0f, 1f) }
                    ?: 0.5f
                else -> 0f
            }
        }
        return total.coerceIn(0f, 1f)
    }

    /**
     * Оставшееся время по скорости выполнения: прошло [elapsedMs] на долю [fraction] работы.
     * null — оценивать пока рано или задача не идёт.
     */
    fun remainingMs(fraction: Float, elapsedMs: Long): Long? {
        if (fraction < MIN_FRACTION_FOR_ESTIMATE || fraction >= 1f || elapsedMs < MIN_ELAPSED_FOR_ESTIMATE_MS) return null
        return (elapsedMs * (1f - fraction) / fraction).toLong()
    }
}
