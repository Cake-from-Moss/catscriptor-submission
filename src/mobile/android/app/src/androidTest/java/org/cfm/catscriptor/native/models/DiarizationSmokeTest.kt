package org.cfm.catscriptor.native.models

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.cfm.catscriptor.audio.core.FilePcmAudioSource
import org.cfm.catscriptor.audio.core.WavReader
import org.cfm.catscriptor.domain.pipeline.PcmAudioSource
import org.cfm.catscriptor.domain.pipeline.SpeechSegment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Настоящий ONNX-инференс диаризации на реальном двухголосом WAV (официальный пример sherpa-onnx, английский).
 * Это проверка загрузки моделей и смены спикера на устройстве, а не оценка качества на русской речи.
 * Перед WAV добавлена цифровая тишина, чтобы убедиться, что метки абсолютные, а не от начала файла примера.
 */
@RunWith(AndroidJUnit4::class)
class DiarizationSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val leadMs = 5_000L

    private fun fixture(): FilePcmAudioSource {
        val file = File.createTempFile("diarization-smoke-", ".f32", context.cacheDir)
        try {
            file.outputStream().buffered().use { output ->
                repeat(leadMs.toInt() / 1000) { output.write(ByteArray(16000 * 4)) }
                instrumentation.context.assets.open("two-speakers-en.wav").use { input ->
                    WavReader.decode(input, { require(it.sampleRate == 16000 && it.channels == 1) }, { samples ->
                        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                        samples.forEach { buffer.putFloat(it) }
                        output.write(buffer.array())
                    })
                }
            }
            return FilePcmAudioSource(file)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    @Test fun realModelsFindTwoSpeakersAtAbsoluteOffsets() = runBlocking {
        assertTrue(Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        fixture().use { audio ->
            val durationMs = audio.sampleCount / 16
            // VAD освобождается до диаризации; аудио остаётся открытым для вызывающего кода.
            val speech = SherpaVoiceActivityDetector(context).use { it.detect(audio) }
            assertTrue("VAD должен найти речь примера", speech.isNotEmpty())
            repeat(2) { round ->
                val started = System.nanoTime()
                val speakers = SherpaDiarizationBackend(context).use { it.diarize(audio, speech) }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                Log.i("DiarizationSmoke", "round=$round elapsed_ms=$elapsedMs audio_ms=$durationMs speakers=$speakers")
                assertEquals("Пример содержит двух говорящих", 2, speakers.map { it.speakerId }.distinct().size)
                assertEquals("speaker_1", speakers.first().speakerId)
                assertTrue(speakers.all { it.speakerId in setOf("speaker_1", "speaker_2") })
                assertTrue("Метки абсолютны: речь начинается после ${leadMs} мс тишины", speakers.first().time.startMs >= leadMs - 500)
                assertTrue(speakers.all { it.time.endMs <= durationMs })
                assertEquals(speakers.sortedBy { it.time.startMs }, speakers)
                assertEquals("PcmAudioSource не закрывается backend", 1, audio.read(0, 1).size)
            }
        }
    }

    @Test fun silenceProducesNoSpeakersAndClosedBackendIsUnusable() = runBlocking {
        fixture().use { audio ->
            val backend = SherpaDiarizationBackend(context)
            assertTrue(backend.diarize(audio, emptyList()).isEmpty())
            backend.close()
            backend.close()
            try {
                backend.diarize(audio, listOf(SpeechSegment(org.cfm.catscriptor.domain.pipeline.TimeRange(5_000, 9_000))))
                fail("Закрытый backend нельзя использовать")
            } catch (_: IllegalStateException) {
            }
            assertEquals(1, audio.read(0, 1).size)
        }
    }
}
