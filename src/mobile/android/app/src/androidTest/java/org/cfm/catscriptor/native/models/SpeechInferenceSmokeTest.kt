package org.cfm.catscriptor.native.models

import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.cfm.catscriptor.audio.core.FilePcmAudioSource
import org.cfm.catscriptor.audio.core.WavReader
import org.cfm.catscriptor.domain.pipeline.PcmAudioSource
import org.cfm.catscriptor.domain.pipeline.SpeechSegment
import org.cfm.catscriptor.domain.pipeline.TimeRange
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Настоящий WAV из модельного архива; ни UI, ни сеть, ни оценочные файлы #11 не используются. */
@RunWith(AndroidJUnit4::class)
class SpeechInferenceSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun digitalSilenceIsEmptyAndModelsReopen() = runBlocking {
        assertTrue(Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission("android.permission.INTERNET"))
        fixture(0).use { audio ->
            repeat(2) {
                val vad = SherpaVoiceActivityDetector(context)
                try {
                    assertTrue(vad.detect(audio).isEmpty())
                } finally {
                    vad.close()
                    vad.close()
                }
                try {
                    vad.detect(audio)
                    fail("Закрытый VAD нельзя использовать")
                } catch (_: IllegalStateException) {
                    // После освобождения нативный указатель недоступен.
                }
                val backend = SherpaTranscriptionBackend(context)
                try {
                    assertEquals("", backend.transcribe(audio, SpeechSegment(TimeRange(0, 1000))).text)
                } finally {
                    backend.close()
                    backend.close()
                }
                assertAsrClosed(backend, audio, SpeechSegment(TimeRange(0, 1000)))
                assertEquals("close ASR не закрывает PCM", 1, audio.read(0, 1).size)
            }
            assertEquals(1, audio.read(0, 1).size)
        }
    }

    @Test fun realRussianVadThenAsrAtAbsoluteOffset() = runBlocking {
        fixture(2).use { audio ->
            val segments = SherpaVoiceActivityDetector(context).use { it.detect(audio) }
            Log.i(TAG, "vad=$segments sample_count=${audio.sampleCount}")
            assertTrue("Две настоящие фразы должны быть найдены", segments.size >= 2)
            assertTrue(segments.first().time.startMs >= 1000)
            assertTrue(segments.zipWithNext().all { (a, b) -> a.time.endMs <= b.time.startMs })
            assertTrue(segments.all { it.time.endMs <= (audio.sampleCount + 15) / 16 })
            val probe = InstrumentationRegistry.getArguments().getString("ctcProbe") == "true"
            // VAD уже освобождён: тяжёлые модели не удерживаются одновременно.
            SherpaTranscriptionBackend(context).use { backend ->
                for (segment in segments) {
                    val result = backend.transcribe(audio, segment)
                    assertEquals(segment.time, result.time)
                    assertTrue(result.text.any { it in 'а'..'я' || it in 'А'..'Я' })
                    Log.i(TAG, "asr_interval=${result.time} text=${result.text}")
                }
                val original = segments.first().time
                val smaller = SpeechSegment(TimeRange(original.startMs + 100, original.endMs - 100))
                assertEquals(smaller.time, backend.transcribe(audio, smaller).time)
            }
            // Старый эксперимент остаётся opt-in и не дублирует тяжёлый decode в обычном smoke.
            if (probe) {
            SherpaModelResource(context).use { asr ->
                for (segment in segments) {
                    val start = segment.time.startMs * 16
                    val end = minOf(segment.time.endMs * 16, audio.sampleCount)
                    require(end - start <= 25 * 16000)
                    val samples = FloatArray((end - start).toInt())
                    var filled = 0
                    while (filled < samples.size) {
                        val block = audio.read(start + filled, minOf(8192, samples.size - filled))
                        check(block.isNotEmpty())
                        block.copyInto(samples, filled)
                        filled += block.size
                    }
                    val result = asr.diagnose(samples, includeCtcFrames = probe)
                    assertTrue(result.text.isNotBlank() && result.text.any { it in 'а'..'я' })
                    Log.i(TAG, "offset_ms=${segment.time.startMs} text=${result.text}")
                    if (probe) {
                        val posterior = requireNotNull(result.ctcPosterior)
                        val alphabet = " абвгдежзийклмнопрстуфхцчшщъыьэюя"
                        val ids = result.tokens.map { token ->
                            require(token.length == 1 && alphabet.indexOf(token[0]) >= 0)
                            alphabet.indexOf(token[0])
                        }
                        val coroutine = currentCoroutineContext()
                        val alignment = posterior.align(ids) { coroutine.ensureActive() }
                        assertEquals(ids.size, alignment.size)
                        assertTrue(alignment.zipWithNext().all { (a, b) -> a.endFrameExclusive <= b.startFrame })
                        val widths = alignment.map { (it.endFrameExclusive - it.startFrame) * posterior.frameShiftMs }
                        Log.i(TAG, "ctc_frames=${posterior.frameCount} token_width_ms=$widths")
                        Log.i(TAG, "ctc_token_frames=$alignment")
                    }
                }
            }
            }
            assertEquals(1, audio.read(0, 1).size)
        }
    }

    @Test fun longRecordingIsReadInBoundedBlocks() = runBlocking {
        fixture(10).use { audio ->
            var largestRead = 0
            val bounded = object : PcmAudioSource by audio {
                override suspend fun read(startSample: Long, maxSamples: Int): FloatArray {
                    largestRead = maxOf(largestRead, maxSamples)
                    return audio.read(startSample, maxSamples)
                }
            }
            val segments = SherpaVoiceActivityDetector(context).use { it.detect(bounded) }
            assertTrue(segments.size >= 10)
            assertTrue(audio.sampleCount > 60 * 16000)
            assertTrue(largestRead <= 8192)
            SherpaTranscriptionBackend(context).use { backend ->
                val segment = SpeechSegment(TimeRange(1000, 28000))
                val result = backend.transcribe(bounded, segment)
                assertEquals(segment.time, result.time)
                assertTrue(result.text.any { it in 'а'..'я' || it in 'А'..'Я' })
            }
            assertTrue(largestRead <= 8192)
            assertEquals(1, audio.read(0, 1).size)
        }
    }

    @Test fun cancellationDuringPcmReadReleasesVadButNotCallerAudio() = runBlocking {
        fixture(1).use { audio ->
            val entered = CompletableDeferred<Unit>()
            val blocked = object : PcmAudioSource by audio {
                override suspend fun read(startSample: Long, maxSamples: Int): FloatArray {
                    entered.complete(Unit)
                    kotlinx.coroutines.awaitCancellation()
                }
            }
            val vad = SherpaVoiceActivityDetector(context)
            val job = async(Dispatchers.Default) { vad.detect(blocked) }
            entered.await()
            job.cancelAndJoin()
            vad.close()
            assertEquals(1, audio.read(0, 1).size)
        }
    }

    @Test fun asrCancellationAndReadFailureReleaseAndReopen() = runBlocking {
        fixture(0).use { audio ->
            val interval = SpeechSegment(TimeRange(0, 1000))
            for (cancel in listOf(true, false)) {
                val entered = CompletableDeferred<Unit>()
                var sourceClosed = false
                val source = object : PcmAudioSource by audio {
                    override suspend fun read(startSample: Long, maxSamples: Int): FloatArray {
                        entered.complete(Unit)
                        if (cancel) kotlinx.coroutines.awaitCancellation()
                        throw java.io.IOException("Ошибка чтения smoke")
                    }
                    override fun close() { sourceClosed = true }
                }
                val backend = SherpaTranscriptionBackend(context)
                // Сначала действительно загружаем модель, затем проверяем освобождение при ошибке.
                assertEquals("", backend.transcribe(audio, interval).text)
                if (cancel) {
                    val job = async(Dispatchers.Default) { backend.transcribe(source, interval) }
                    entered.await()
                    job.cancelAndJoin()
                } else {
                    try {
                        backend.transcribe(source, interval)
                        fail("Ожидалась ошибка чтения")
                    } catch (_: java.io.IOException) { }
                }
                // Проверяем автоматическое закрытие, а не эффект явного close ниже.
                assertAsrClosed(backend, audio, interval)
                assertFalse("Ошибка/отмена ASR не закрывает переданный PCM", sourceClosed)
                backend.close()
                backend.close()
                assertAsrClosed(backend, audio, interval)
                assertFalse("Явный close ASR не закрывает переданный PCM", sourceClosed)
                assertEquals(1, audio.read(0, 1).size)
            }
            SherpaTranscriptionBackend(context).use {
                assertEquals("", it.transcribe(audio, interval).text)
            }
        }
    }

    private suspend fun assertAsrClosed(
        backend: SherpaTranscriptionBackend,
        audio: PcmAudioSource,
        interval: SpeechSegment,
    ) {
        var reads = 0
        val observed = object : PcmAudioSource by audio {
            override suspend fun read(startSample: Long, maxSamples: Int): FloatArray {
                reads++
                return audio.read(startSample, maxSamples)
            }
        }
        try {
            backend.transcribe(observed, interval)
            fail("Закрытый ASR нельзя использовать")
        } catch (_: IllegalStateException) {
            // Ошибка должна возникать до чтения и до обращения к освобождённой модели.
        }
        assertEquals("Закрытый ASR не читает PCM", 0, reads)
    }

    private fun fixture(repetitions: Int): FilePcmAudioSource {
        val file = File.createTempFile("speech-inference-", ".f32", context.cacheDir)
        try {
            file.outputStream().buffered().use { output ->
                fun silence() = repeat(8) { output.write(ByteArray(16000)) }
                silence()
                repeat(repetitions) {
                    instrumentation.context.assets.open("sherpa-example.wav").use { input ->
                        WavReader.decode(input, { format ->
                            require(format.sampleRate == 16000 && format.channels == 1)
                        }, { samples ->
                            val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                            samples.forEach { buffer.putFloat(it) }
                            output.write(buffer.array())
                        })
                    }
                    silence()
                }
            }
            return FilePcmAudioSource(file)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    companion object { private const val TAG = "SpeechInferenceSmoke" }
}
