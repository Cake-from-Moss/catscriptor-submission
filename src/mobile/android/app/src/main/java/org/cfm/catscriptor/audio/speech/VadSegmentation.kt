package org.cfm.catscriptor.audio.speech

import org.cfm.catscriptor.domain.pipeline.SpeechSegment
import org.cfm.catscriptor.domain.pipeline.TimeRange

/** Начальные параметры, не оптимизированные на оценочных записях. */
data class SileroVadSettings(
    val threshold: Float = 0.5f,
    val minSpeechMs: Int = 100,
    val minSilenceMs: Int = 300,
    val speechPadMs: Int = 150,
) {
    init {
        require(threshold > 0.15f && threshold <= 1f)
        require(minSpeechMs in 1..10_000 && minSilenceMs in 1..10_000)
        require(speechPadMs in 0..5_000)
    }
}

/**
 * Сегментация вероятностей Silero без нативного накопителя аудио и его 60-секундного лимита.
 * Все счётчики — Long в сэмплах. Промежуточные blank/тишина не превращаются в PCM-буферы.
 */
internal class VadSegmentation(private val settings: SileroVadSettings) {
    private var position = 0L
    private var candidate = -1L
    private var speechStart = -1L
    private var silenceStart = -1L
    private val ranges = mutableListOf<TimeRange>()

    fun accept(probability: Float, sampleCount: Int) {
        require(probability.isFinite() && probability in 0f..1f && sampleCount in 1..512)
        val start = position
        position = Math.addExact(position, sampleCount.toLong())
        if (speechStart < 0) {
            if (probability >= settings.threshold) {
                if (candidate < 0) candidate = start
                if (position - candidate >= settings.minSpeechMs * 16L) {
                    speechStart = candidate
                    silenceStart = -1L
                }
            } else {
                candidate = -1L
            }
        } else if (probability >= settings.threshold - 0.15f) {
            silenceStart = -1L
        } else {
            if (silenceStart < 0) silenceStart = start
            if (position - silenceStart >= settings.minSilenceMs * 16L) {
                append(speechStart, silenceStart)
                speechStart = -1L
                candidate = -1L
                silenceStart = -1L
            }
        }
    }

    fun finish(): List<SpeechSegment> {
        if (speechStart >= 0) append(speechStart, if (silenceStart >= 0) silenceStart else position)
        speechStart = -1L
        return ranges.map { SpeechSegment(it.copy(endMs = minOf(it.endMs, ceilMs(position)))) }
    }

    private fun append(start: Long, end: Long) {
        val paddedStart = maxOf(0L, start - settings.speechPadMs * 16L)
        val paddedEnd = Math.addExact(end, settings.speechPadMs * 16L)
        val time = TimeRange(paddedStart / 16, ceilMs(paddedEnd))
        val previous = ranges.lastOrNull()
        if (previous != null && time.startMs <= previous.endMs) {
            ranges[ranges.lastIndex] = TimeRange(previous.startMs, maxOf(previous.endMs, time.endMs))
        } else {
            ranges += time
        }
    }

    private fun ceilMs(samples: Long): Long = samples / 16 + if (samples % 16 == 0L) 0 else 1
}
