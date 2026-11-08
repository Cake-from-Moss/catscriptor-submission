package org.cfm.catscriptor.domain.diarization

import kotlin.math.abs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.cfm.catscriptor.domain.pipeline.PcmAudioSource
import org.cfm.catscriptor.domain.pipeline.SpeakerSegment
import org.cfm.catscriptor.domain.pipeline.SpeechSegment
import org.cfm.catscriptor.domain.pipeline.TimeRange

/** Эмбеддинг голоса по аудио; null, если аудио слишком коротко для модели. */
interface SpeakerEmbedder {
    suspend fun embed(samples: FloatArray): FloatArray?
}

/**
 * Окна эмбеддингов — как в рецепте 3D-Speaker (1,5 с с шагом 0,75 с).
 *
 * @property minWindowMs сегмент VAD короче окна, но не короче этого, даёт одно окно на всю длину.
 * @property boundarySnapMs радиус поиска паузы вокруг смены спикера; 0 — не уточнять границу.
 *
 * Хвост сегмента после последнего полного окна (короче [hopMs]) отдельного окна не получает и
 * принадлежит спикеру последнего окна: окно, выровненное по концу реплики, захватывает затухание
 * и паддинг VAD, и на записи с 4 спикерами такие окна собирались в пятого «спикера».
 */
data class EmbeddingDiarizerSettings(
    val windowMs: Long = 1_500,
    val hopMs: Long = 750,
    val minWindowMs: Long = 500,
    val boundarySnapMs: Long = 400,
    val readBlockSamples: Int = 8192,
    val clustering: SpeakerClusteringSettings = SpeakerClusteringSettings(),
) {
    init {
        require(hopMs in 1..windowMs && minWindowMs in 1..windowMs && readBlockSamples > 0)
        require(boundarySnapMs in 0..hopMs)
    }
}

/**
 * Диаризация по эмбеддингам коротких окон внутри речи VAD.
 *
 * 1. Каждый сегмент VAD режется на перекрывающиеся окна; для каждого окна ERes2Net даёт эмбеддинг.
 * 2. [SpeakerClustering] сам определяет число спикеров и отбрасывает мелкие «ложные» кластеры.
 * 3. Внутри сегмента VAD смена спикера ставится посередине перекрытия соседних окон с разными
 *    метками; одиночное окно между двумя окнами одного спикера считается шумом и сглаживается.
 *    Сегмент без окон (короче [EmbeddingDiarizerSettings.minWindowMs]) получает спикера ближайшего
 *    по времени окна: так его речь всё равно попадёт в ASR, а не потеряется.
 *
 * Перекрытия речи не выделяются: каждый момент принадлежит одному спикеру. Итоговые `speaker_N`
 * нумеруются по первому появлению. PCM читается окнами, вся запись в память не загружается.
 */
class EmbeddingDiarizer(
    private val embedderProvider: () -> SpeakerEmbedder,
    private val settings: EmbeddingDiarizerSettings = EmbeddingDiarizerSettings(),
    /** Число спикеров из настроек; null — автоопределение (см. [SpeakerClustering.cluster]). */
    private val targetSpeakers: Int? = null,
) {
    private val clustering = SpeakerClustering(settings.clustering)

    private class Window(val segment: Int, val time: TimeRange, val representsMs: Long)

    suspend fun diarize(audio: PcmAudioSource, speech: List<SpeechSegment>): List<SpeakerSegment> {
        val durationMs = audio.sampleCount * 1000 / PcmAudioSource.SAMPLE_RATE_HZ
        val segments = speech.map { it.time }
            .mapNotNull { range -> minOf(range.endMs, durationMs).takeIf { it > range.startMs }?.let { TimeRange(range.startMs, it) } }
            .sortedBy { it.startMs }
        if (segments.isEmpty()) return emptyList()

        val planned = segments.flatMapIndexed { index, range -> windows(index, range) }
        val embedded = ArrayList<Window>(planned.size)
        val vectors = ArrayList<FloatArray>(planned.size)
        if (planned.isNotEmpty()) {
            val embedder = embedderProvider()
            for (window in planned) {
                currentCoroutineContext().ensureActive()
                val vector = embedder.embed(read(audio, window.time)) ?: continue
                embedded += window
                vectors += vector
            }
        }
        currentCoroutineContext().ensureActive()
        val labels = clustering.cluster(vectors, embedded.map { it.representsMs }, targetSpeakers)

        val result = ArrayList<Pair<Int, TimeRange>>()
        for ((index, range) in segments.withIndex()) {
            val own = embedded.indices.filter { embedded[it].segment == index }
            if (own.isEmpty()) {
                result += nearestLabel(range, embedded, labels) to range
                continue
            }
            val smoothed = smooth(own.map { labels[it] })
            var start = range.startMs
            for (i in 0 until own.size - 1) {
                if (smoothed[i] == smoothed[i + 1]) continue
                val left = embedded[own[i]].time
                val right = embedded[own[i + 1]].time
                val estimate = ((right.startMs + left.endMs) / 2).coerceIn(start, range.endMs)
                val boundary = snapToPause(audio, estimate, start, range.endMs)
                if (boundary > start) result += smoothed[i] to TimeRange(start, boundary)
                start = boundary
            }
            if (range.endMs > start) result += smoothed.last() to TimeRange(start, range.endMs)
        }
        return number(result)
    }

    private fun windows(segment: Int, range: TimeRange): List<Window> {
        val length = range.endMs - range.startMs
        if (length < settings.windowMs) {
            return if (length >= settings.minWindowMs) listOf(Window(segment, range, length)) else emptyList()
        }
        val result = ArrayList<Window>()
        var start = range.startMs
        while (start + settings.windowMs <= range.endMs) {
            result += Window(segment, TimeRange(start, start + settings.windowMs), settings.hopMs)
            start += settings.hopMs
        }
        return result
    }

    /**
     * Уточнение границы смены спикера по энергии сигнала.
     *
     * Середина перекрытия окон ошибается до ±0,375 с (половина шага), и граница попадает внутрь
     * слова: ASR получает два обрубка, и слово на стыке теряется в обоих ходах. Прогон GigaAM v3
     * на склеенном диалоге показал: сдвиг границы на 200–350 мс даёт до 13% WER, а перенос
     * границы в самый тихий участок в пределах ±[EmbeddingDiarizerSettings.boundarySnapMs]
     * возвращает WER к исходному. Между репликами разных людей почти всегда есть короткая пауза
     * или вдох, даже если VAD склеил их в один сегмент (minSilenceMs больше паузы).
     *
     * Кадр 20 мс с шагом 10 мс; среди кадров не громче двукратного минимума выбирается ближайший
     * к исходной оценке: при ровной тишине граница не уезжает на край области поиска.
     */
    private suspend fun snapToPause(audio: PcmAudioSource, estimate: Long, lowerMs: Long, upperMs: Long): Long {
        val radius = settings.boundarySnapMs
        if (radius == 0L) return estimate
        val from = maxOf(lowerMs, estimate - radius)
        val to = minOf(upperMs, estimate + radius)
        if (to - from < FRAME_MS) return estimate
        val samples = read(audio, TimeRange(from, to))
        val frame = (FRAME_MS * PER_MS).toInt()
        val hop = (HOP_MS * PER_MS).toInt()
        val centers = ArrayList<Long>()
        val energies = ArrayList<Double>()
        var offset = 0
        while (offset + frame <= samples.size) {
            var sum = 0.0
            for (k in offset until offset + frame) sum += samples[k].toDouble() * samples[k]
            energies += sum / frame
            centers += from + (offset + frame / 2) / PER_MS
            offset += hop
        }
        if (energies.isEmpty()) return estimate
        val limit = energies.min() * 2 + 1e-12
        val best = energies.indices.filter { energies[it] <= limit }.minBy { abs(centers[it] - estimate) }
        return centers[best].coerceIn(lowerMs, upperMs)
    }

    /** Метка A B A внутри одного сегмента — почти наверняка ошибка окна, а не реплика длиной 0,75 с. */
    private fun smooth(labels: List<Int>): List<Int> {
        if (labels.size < 3) return labels
        val result = labels.toMutableList()
        for (i in 1 until labels.lastIndex) {
            if (labels[i - 1] == labels[i + 1] && labels[i] != labels[i - 1]) result[i] = labels[i - 1]
        }
        return result
    }

    private fun nearestLabel(range: TimeRange, windows: List<Window>, labels: IntArray): Int {
        if (windows.isEmpty()) return 0
        val center = (range.startMs + range.endMs) / 2
        val nearest = windows.indices.minBy { abs((windows[it].time.startMs + windows[it].time.endMs) / 2 - center) }
        return labels[nearest]
    }

    private fun number(raw: List<Pair<Int, TimeRange>>): List<SpeakerSegment> {
        val order = LinkedHashMap<Int, String>()
        return raw.sortedBy { it.second.startMs }.map { (label, time) ->
            SpeakerSegment(order.getOrPut(label) { "speaker_${order.size + 1}" }, time)
        }
    }

    private suspend fun read(audio: PcmAudioSource, time: TimeRange): FloatArray {
        val start = time.startMs * PER_MS
        val end = minOf(time.endMs * PER_MS, audio.sampleCount)
        require(start < end) { "Окно вне PCM" }
        val samples = FloatArray(Math.toIntExact(end - start))
        var filled = 0
        while (filled < samples.size) {
            currentCoroutineContext().ensureActive()
            val requested = minOf(settings.readBlockSamples, samples.size - filled)
            val block = audio.read(start + filled, requested)
            check(block.isNotEmpty() && block.size <= requested) { "Нарушен контракт чтения PCM" }
            block.copyInto(samples, filled)
            filled += block.size
        }
        return samples
    }

    private companion object {
        const val PER_MS = PcmAudioSource.SAMPLE_RATE_HZ / 1000L
        const val FRAME_MS = 20L
        const val HOP_MS = 10L
    }
}
