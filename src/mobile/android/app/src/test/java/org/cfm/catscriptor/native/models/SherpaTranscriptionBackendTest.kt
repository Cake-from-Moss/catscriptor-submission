package org.cfm.catscriptor.native.models

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.cfm.catscriptor.domain.pipeline.PcmAudioSource
import org.cfm.catscriptor.domain.pipeline.SpeechSegment
import org.cfm.catscriptor.domain.pipeline.TimeRange
import org.junit.Assert.*
import org.junit.Test

class SherpaTranscriptionBackendTest {
    private class Audio(override val sampleCount: Long) : PcmAudioSource {
        val offsets = mutableListOf<Long>()
        var closed = false
        override suspend fun read(startSample: Long, maxSamples: Int): FloatArray {
            offsets += startSample
            require(maxSamples <= 8192)
            return FloatArray(minOf(997L, maxSamples.toLong(), sampleCount - startSample).toInt())
        }
        override fun close() { closed = true }
    }

    private class Resource : ModelResource {
        var closes = 0
        override fun close() { closes++ }
    }

    @Test fun chunksPartialReadsAndAbsoluteSmallerInterval() = runBlocking {
        val audio = Audio(60 * 16000L)
        val resource = Resource()
        val chunks = mutableListOf<Int>()
        val backend = SherpaTranscriptionBackend({ chunks += it.size; " речь " }, resource)
        val time = TimeRange(1234, 52335)
        val result = backend.transcribe(audio, SpeechSegment(time))
        assertEquals(time, result.time)
        assertEquals("речь речь речь", result.text)
        // Ровная тишина: разрез в последнем кадре порции (центр 20-мс кадра, 160 сэмплов до конца).
        assertEquals(listOf(399840, 399840, 17936), chunks)
        assertEquals(1234 * 16L, audio.offsets.first())
        assertEquals(0, resource.closes)
        backend.close()
        backend.close()
        assertEquals(1, resource.closes)
        assertFalse(audio.closed)
    }

    @Test fun quietCutFindsPauseInsideSearchZone() {
        val samples = FloatArray(SherpaTranscriptionBackend.MAX_CHUNK_SAMPLES) { 0.5f }
        // Пауза 100 мс за 1,5 с до конца порции.
        val pause = samples.size - 24_000
        samples.fill(0f, pause, pause + 1_600)
        val cut = SherpaTranscriptionBackend.quietCut(samples)
        assertTrue("разрез $cut внутри паузы", cut in pause..pause + 1_600)
    }

    @Test fun quietCutDoesNotLookBeforeSearchZone() {
        val samples = FloatArray(SherpaTranscriptionBackend.MAX_CHUNK_SAMPLES) { 0.5f }
        samples.fill(0f, 0, 16_000)
        val cut = SherpaTranscriptionBackend.quietCut(samples)
        assertTrue(cut >= samples.size - SherpaTranscriptionBackend.CUT_SEARCH_SAMPLES)
    }

    @Test fun longOffsetsAndLastPartialMillisecondAndBlankText() = runBlocking {
        val startMs = Int.MAX_VALUE.toLong()
        val audio = Audio(startMs * 16 + 17)
        val backend = SherpaTranscriptionBackend({ assertEquals(17, it.size); "" }, Resource())
        backend.use {
            val time = TimeRange(startMs, startMs + 2)
            val result = it.transcribe(audio, SpeechSegment(time))
            assertEquals("", result.text)
            assertEquals("Округление последней мс не меняет интервал результата", time, result.time)
        }
        assertEquals(startMs * 16, audio.offsets.first())
    }

    @Test fun invalidDurationOverflowAndReadFailuresRelease() = runBlocking {
        for (time in listOf(TimeRange(0, 1001), TimeRange(0, Long.MAX_VALUE))) {
            val resource = Resource()
            val backend = SherpaTranscriptionBackend({ error("Не должен распознавать") }, resource)
            try {
                backend.transcribe(Audio(16000), SpeechSegment(time))
                fail("Ожидалась ошибка интервала")
            } catch (_: IllegalArgumentException) { }
            assertEquals(1, resource.closes)
        }
        for (block in listOf(floatArrayOf(), floatArrayOf(Float.NaN), FloatArray(8193))) {
            val resource = Resource()
            val source = object : PcmAudioSource {
                override val sampleCount = 16000L
                override suspend fun read(startSample: Long, maxSamples: Int) = block
                override fun close() = error("PCM принадлежит вызывающему коду")
            }
            val backend = SherpaTranscriptionBackend({ error("Не должен распознавать") }, resource)
            try {
                backend.transcribe(source, SpeechSegment(TimeRange(0, 1000)))
                fail("Ожидалась ошибка чтения")
            } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
            assertEquals(1, resource.closes)
        }
    }

    @Test fun cancellationClosesResourceNotSource() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val audio = Audio(16000)
        val source = object : PcmAudioSource by audio {
            override suspend fun read(startSample: Long, maxSamples: Int): FloatArray {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val resource = Resource()
        val backend = SherpaTranscriptionBackend({ "" }, resource)
        val job = async { backend.transcribe(source, SpeechSegment(TimeRange(0, 1000))) }
        entered.await()
        job.cancelAndJoin()
        backend.close()
        assertEquals(1, resource.closes)
        assertFalse(audio.closed)
    }

    @Test fun decoderFailureAndCancellationRelease() = runBlocking {
        val audio = Audio(16000)
        val resource = Resource()
        val failed = SherpaTranscriptionBackend({ throw java.io.IOException("Ошибка decode") }, resource)
        try {
            failed.transcribe(audio, SpeechSegment(TimeRange(0, 1000)))
            fail("Ожидалась ошибка decode")
        } catch (_: java.io.IOException) { }
        assertEquals(1, resource.closes)

        val entered = CompletableDeferred<Unit>()
        val cancelledResource = Resource()
        val cancelled = SherpaTranscriptionBackend({ entered.complete(Unit); awaitCancellation() }, cancelledResource)
        val job = async { cancelled.transcribe(audio, SpeechSegment(TimeRange(0, 1000))) }
        entered.await()
        job.cancelAndJoin()
        assertEquals(1, cancelledResource.closes)
        assertFalse(audio.closed)
    }
}
