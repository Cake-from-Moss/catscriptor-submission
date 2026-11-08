package org.cfm.catscriptor.domain.diarization

import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.cfm.catscriptor.domain.pipeline.PcmAudioSource
import org.cfm.catscriptor.domain.pipeline.SpeakerSegment
import org.cfm.catscriptor.domain.pipeline.SpeechSegment
import org.cfm.catscriptor.domain.pipeline.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingDiarizerTest {
    /**
     * PCM, где «голос» закодирован уровнем сигнала: speakerAt(ms) → номер голоса.
     * Фейковый эмбеддер превращает средний уровень окна в смесь векторов голосов — окно на стыке
     * двух спикеров получает промежуточный эмбеддинг, как у настоящей модели.
     */
    private class VoicePcm(private val durationMs: Long, private val speakerAt: (Long) -> Int) : PcmAudioSource {
        override val sampleCount = durationMs * 16
        var reads = 0
        override suspend fun read(startSample: Long, maxSamples: Int): FloatArray {
            reads++
            val count = minOf(maxSamples.toLong(), sampleCount - startSample).toInt()
            return FloatArray(count) { level(speakerAt((startSample + it) / 16)) }
        }
        override fun close() = Unit
    }

    private class LevelEmbedder(voices: Int) : SpeakerEmbedder {
        private val random = Random(11)
        private val centers = List(voices) { FloatArray(32) { random.nextFloat() * 2 - 1 } }
        var calls = 0
        override suspend fun embed(samples: FloatArray): FloatArray {
            calls++
            val counts = IntArray(centers.size)
            samples.forEach { counts[voiceOf(it)]++ }
            return FloatArray(32) { d -> centers.indices.sumOf { counts[it] * centers[it][d].toDouble() }.toFloat() / samples.size }
        }
    }

    private fun speech(vararg ranges: Pair<Long, Long>) = ranges.map { SpeechSegment(TimeRange(it.first, it.second)) }

    private fun diarizer(embedder: SpeakerEmbedder) = EmbeddingDiarizer({ embedder })

    @Test
    fun speakerChangeInsideOneVadSegmentIsFound() = runTest {
        // A говорит 0–6 с, B — 6–12 с без паузы: прежняя сегментация видела здесь одного спикера.
        val pcm = VoicePcm(12_000) { if (it < 6_000) 0 else 1 }
        val result = diarizer(LevelEmbedder(2)).diarize(pcm, speech(0L to 12_000L))
        assertEquals(listOf("speaker_1", "speaker_2"), result.map { it.speakerId })
        assertEquals(0, result[0].time.startMs)
        assertTrue("граница около 6 с: ${result[0].time}", kotlin.math.abs(result[0].time.endMs - 6_000) <= 750)
        assertEquals(result[0].time.endMs, result[1].time.startMs)
        assertEquals(12_000, result[1].time.endMs)
    }

    @Test
    fun dialogueOfFourSpeakersGivesFourIdsNotMore() = runTest {
        // Реплики по 4 с с паузой 0,5 с, 4 человека по кругу, 3 круга.
        val turns = (0 until 12).map { it * 4_500L to it * 4_500L + 4_000L }
        val pcm = VoicePcm(54_000) { ms -> ((ms / 4_500) % 4).toInt() }
        val result = diarizer(LevelEmbedder(4)).diarize(pcm, speech(*turns.toTypedArray()))
        assertEquals(4, result.map { it.speakerId }.distinct().size)
        assertEquals(List(12) { "speaker_${it % 4 + 1}" }, result.map { it.speakerId })
        turns.zip(result).forEach { (turn, segment) -> assertEquals(TimeRange(turn.first, turn.second), segment.time) }
    }

    @Test
    fun shortSegmentWithoutWindowGetsNearestSpeakerInsteadOfBeingDropped() = runTest {
        val pcm = VoicePcm(20_000) { ms -> if (ms < 8_000) 0 else 1 }
        val result = diarizer(LevelEmbedder(2)).diarize(pcm, speech(0L to 4_000L, 4_300L to 4_600L, 10_000L to 14_000L))
        assertEquals(
            listOf(
                SpeakerSegment("speaker_1", TimeRange(0, 4_000)),
                SpeakerSegment("speaker_1", TimeRange(4_300, 4_600)),
                SpeakerSegment("speaker_2", TimeRange(10_000, 14_000)),
            ),
            result,
        )
    }

    @Test
    fun speakerChangeBoundarySnapsToPauseBetweenVoices() = runTest {
        // A 0–5,9 с, пауза 5,9–6,0 (тишина, VAD её не разрывает), B 6,0–12 с.
        val pcm = PausePcm(12_000, pause = 5_900L until 6_000L) { if (it < 6_000) 0 else 1 }
        val result = diarizer(LevelEmbedder(2)).diarize(pcm, speech(0L to 12_000L))
        assertEquals(listOf("speaker_1", "speaker_2"), result.map { it.speakerId })
        assertTrue("граница в паузе: ${result[0].time}", result[0].time.endMs in 5_900L..6_000L)
    }

    @Test
    fun boundarySnapCanBeDisabled() = runTest {
        val pcm = PausePcm(12_000, pause = 5_900L until 6_000L) { if (it < 6_000) 0 else 1 }
        val plain = EmbeddingDiarizer({ LevelEmbedder(2) }, EmbeddingDiarizerSettings(boundarySnapMs = 0))
            .diarize(pcm, speech(0L to 12_000L))
        assertEquals(2, plain.size)
        assertEquals(plain[0].time.endMs, plain[1].time.startMs)
    }

    /** Как [VoicePcm], но в [pause] — цифровая тишина. */
    private class PausePcm(durationMs: Long, private val pause: LongRange, private val speakerAt: (Long) -> Int) : PcmAudioSource {
        override val sampleCount = durationMs * 16
        override suspend fun read(startSample: Long, maxSamples: Int): FloatArray {
            val count = minOf(maxSamples.toLong(), sampleCount - startSample).toInt()
            return FloatArray(count) {
                val ms = (startSample + it) / 16
                if (ms in pause) 0f else level(speakerAt(ms))
            }
        }
        override fun close() = Unit
    }

    @Test
    fun noSpeechNoEmbedderCalls() = runTest {
        val embedder = LevelEmbedder(1)
        val pcm = VoicePcm(5_000) { 0 }
        assertTrue(diarizer(embedder).diarize(pcm, emptyList()).isEmpty())
        assertEquals(0, embedder.calls)
    }

    @Test
    fun segmentsAreClampedToAudioAndSorted() = runTest {
        val pcm = VoicePcm(5_000) { 0 }
        val result = diarizer(LevelEmbedder(1)).diarize(pcm, speech(3_000L to 9_000L, 0L to 2_000L))
        assertEquals(listOf(TimeRange(0, 2_000), TimeRange(3_000, 5_000)), result.map { it.time })
        assertEquals(setOf("speaker_1"), result.map { it.speakerId }.toSet())
    }

    private companion object {
        fun level(voice: Int) = 0.1f + voice * 0.2f
        fun voiceOf(sample: Float) = Math.round((sample - 0.1f) / 0.2f)
    }
}
