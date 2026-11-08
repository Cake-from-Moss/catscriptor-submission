package org.cfm.catscriptor.native.models

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cfm.catscriptor.domain.pipeline.AsrSegment
import org.cfm.catscriptor.domain.pipeline.PcmAudioSource
import org.cfm.catscriptor.domain.pipeline.SpeechSegment
import org.cfm.catscriptor.domain.pipeline.TranscriptionBackend

/**
 * GigaAM v3 CTC: один ленивый recognizer, абсолютный интервал целиком от вызывающего кода.
 * Порции до 25 секунд не перекрываются; разрез ставится в самый тихий кадр последних
 * [CUT_SEARCH_SAMPLES] порции (см. [quietCut]), чтобы не рубить слово пополам. Конкатенация
 * текста не создаёт пословных меток и не требует экспериментальных CTC posterior.
 * После ошибки или отмены backend закрывается; для повтора нужен новый экземпляр.
 */
class SherpaTranscriptionBackend internal constructor(
    private val recognize: suspend (FloatArray) -> String,
    private val resource: ModelResource,
) : TranscriptionBackend {
    constructor(context: Context, numThreads: Int = 2) : this(SherpaModelResource(context, numThreads))

    private constructor(model: SherpaModelResource) : this({ model.diagnose(it).text }, model)

    private val operation = Mutex()
    private val lifecycle = Any()
    private var closed = false

    override suspend fun transcribe(audio: PcmAudioSource, segment: SpeechSegment): AsrSegment =
        try {
            withContext(Dispatchers.IO) {
                operation.withLock {
                    checkOpen()
                    val coroutine = currentCoroutineContext()
                    coroutine.ensureActive()
                    val count = audio.sampleCount
                    require(count >= 0) { "Отрицательная длина PCM" }
                    val time = segment.time
                    require(time.endMs <= Long.MAX_VALUE / SAMPLES_PER_MS) { "Переполнение смещения PCM" }
                    val start = time.startMs * SAMPLES_PER_MS
                    val requestedEnd = time.endMs * SAMPLES_PER_MS
                    // Последняя неполная миллисекунда допустима, но не произвольный выход за запись.
                    val durationMs = count / SAMPLES_PER_MS + if (count % SAMPLES_PER_MS == 0L) 0 else 1
                    require(time.endMs <= durationMs && start < count) { "Интервал вне PCM" }
                    val end = minOf(requestedEnd, count)
                    val text = StringBuilder()
                    var offset = start
                    while (offset < end) {
                        coroutine.ensureActive()
                        checkOpen()
                        val samples = FloatArray(minOf(MAX_CHUNK_SAMPLES.toLong(), end - offset).toInt())
                        var filled = 0
                        while (filled < samples.size) {
                            coroutine.ensureActive()
                            val requested = minOf(8192, samples.size - filled)
                            val block = audio.read(offset + filled, requested)
                            coroutine.ensureActive()
                            check(block.isNotEmpty() && block.size <= requested) { "Нарушен контракт чтения PCM" }
                            require(block.all { it.isFinite() && it in -1f..1f }) { "Некорректный PCM" }
                            block.copyInto(samples, filled)
                            filled += block.size
                        }
                        checkOpen()
                        coroutine.ensureActive()
                        // Не последняя порция: обрезаем по паузе, остаток пойдёт в следующую.
                        val cut = if (offset + samples.size < end) quietCut(samples) else samples.size
                        val chunk = if (cut == samples.size) samples else samples.copyOf(cut)
                        val part = recognize(chunk).trim()
                        coroutine.ensureActive()
                        checkOpen()
                        if (part.isNotEmpty()) {
                            if (text.isNotEmpty()) text.append(' ')
                            text.append(part)
                        }
                        offset += cut
                    }
                    AsrSegment(time = time, text = text.toString())
                }
            }
        } catch (error: Throwable) {
            close()
            throw error
        }

    private fun checkOpen() = synchronized(lifecycle) {
        check(!closed) { "ASR уже закрыт" }
    }

    /** Может ожидать текущий JNI decode; не вызывать из UI-потока. PCM не принадлежит backend. */
    override fun close() = synchronized(lifecycle) {
        if (!closed) {
            closed = true
            resource.close()
        }
    }

    companion object {
        private const val SAMPLES_PER_MS = 16L
        internal const val MAX_CHUNK_SAMPLES = 25 * 16000
        internal const val CUT_SEARCH_SAMPLES = 3 * 16000
        private const val FRAME = 320
        private const val HOP = 160

        /**
         * Индекс разреза: центр самого тихого 20-мс кадра в последних 3 с порции.
         * Пауза между словами на 20–40 дБ тише речи, поэтому минимум энергии почти всегда
         * попадает между словами; потеря порции до 3 с не важна — остаток уйдёт в следующую.
         */
        internal fun quietCut(samples: FloatArray): Int {
            if (samples.size <= CUT_SEARCH_SAMPLES) return samples.size
            var best = samples.size
            var bestEnergy = Double.MAX_VALUE
            var start = samples.size - CUT_SEARCH_SAMPLES
            while (start + FRAME <= samples.size) {
                var sum = 0.0
                for (k in start until start + FRAME) sum += samples[k].toDouble() * samples[k]
                // При равной энергии — более поздний кадр: порции длиннее, разрезов меньше.
                if (sum <= bestEnergy) {
                    bestEnergy = sum
                    best = start + FRAME / 2
                }
                start += HOP
            }
            return best
        }
    }
}
