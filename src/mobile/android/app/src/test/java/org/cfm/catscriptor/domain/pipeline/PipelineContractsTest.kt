package org.cfm.catscriptor.domain.pipeline

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineContractsTest {
    @Test
    fun everyStageCanBeSubstitutedWithoutAndroidOrNativeModels() = runSuspend {
        val closedModels = mutableListOf<String>()
        val audio = TestAudio()
        val input = AudioInput("content://recordings/dialogue")
        // Ненулевое смещение помогает обнаружить ошибочный отсчёт меток от начала фрагмента.
        val range = TimeRange(10_000, 12_000)
        val speech = listOf(SpeechSegment(range))
        val recognized = AsrSegment(range, "Привет, кот")
        val speakers = listOf(
            SpeakerSegment("speaker_1", range),
        )
        val expected = Transcript(listOf(
            Utterance("speaker_1", range.startMs, range.endMs, recognized.text),
        ))
        val decoder: AudioDecoder = object : AudioDecoder {
            override suspend fun decode(input: AudioInput): PcmAudioSource {
                assertEquals("content://recordings/dialogue", input.documentId)
                return audio
            }
        }
        val vad: VoiceActivityDetector = object : VoiceActivityDetector {
            override suspend fun detect(audio: PcmAudioSource): List<SpeechSegment> {
                assertEquals(16_000, PcmAudioSource.SAMPLE_RATE_HZ)
                assertEquals(160, audio.read(160_000, 160).size)
                return speech
            }
            override fun close() { closedModels += "vad" }
        }
        val asr: TranscriptionBackend = object : TranscriptionBackend {
            override suspend fun transcribe(audio: PcmAudioSource, segment: SpeechSegment): AsrSegment {
                assertEquals(range, segment.time)
                assertFalse((audio as TestAudio).closed)
                return recognized
            }
            override fun close() { closedModels += "asr" }
        }
        val diarizer: DiarizationBackend = object : DiarizationBackend {
            override suspend fun diarize(
                audio: PcmAudioSource,
                speech: List<SpeechSegment>,
            ): List<SpeakerSegment> {
                assertEquals(listOf(SpeechSegment(range)), speech)
                return speakers
            }
            override fun close() { closedModels += "diarizer" }
        }
        val merger: UtteranceMerger = object : UtteranceMerger {
            override suspend fun merge(asr: List<AsrSegment>, speakers: List<SpeakerSegment>): Transcript {
                assertEquals(listOf(recognized), asr)
                assertEquals(1, speakers.size)
                return expected
            }
        }
        val corrector: TextCorrectionBackend = object : TextCorrectionBackend {
            override suspend fun correct(utterance: Utterance): String = utterance.text + "!"
            override fun close() { closedModels += "llm" }
        }
        val exporter: TranscriptExporter = object : TranscriptExporter {
            override suspend fun export(
                transcript: Transcript,
                format: TranscriptFormat,
                output: OutputStream,
            ) {
                assertEquals(TranscriptFormat.TEXT, format)
                assertEquals(expected.transcript.map { it.startMs to it.endMs },
                    transcript.transcript.map { it.startMs to it.endMs })
                output.write(transcript.transcript.joinToString("\n") { it.text }.toByteArray(Charsets.UTF_8))
            }
        }

        decoder.decode(input).use { source ->
            val detected = vad.use { it.detect(source) }
            val segments = asr.use { backend -> detected.map { backend.transcribe(source, it) } }
            val diarized = diarizer.use { it.diarize(source, detected) }
            val merged = merger.merge(segments, diarized)
            val corrected = corrector.use { backend ->
                Transcript(merged.transcript.map { it.copy(text = backend.correct(it)) })
            }
            val output = ByteArrayOutputStream()
            exporter.export(corrected, TranscriptFormat.TEXT, output)
            assertEquals("Привет, кот!", output.toString("UTF-8"))
            assertEquals(expected.transcript.map { it.startMs }, corrected.transcript.map { it.startMs })
        }
        assertTrue(audio.closed)
        assertEquals(listOf("vad", "asr", "diarizer", "llm"), closedModels)
    }

    @Test
    fun stageErrorsPropagateAndCallerCanReleaseResources() {
        val audio = TestAudio()
        var modelClosed = false
        val asr = object : TranscriptionBackend {
            override suspend fun transcribe(audio: PcmAudioSource, segment: SpeechSegment): AsrSegment {
                throw IllegalStateException("ASR inference failed")
            }
            override fun close() { modelClosed = true }
        }
        val error = assertThrows(IllegalStateException::class.java) {
            runSuspend {
                audio.use { source ->
                    asr.use { it.transcribe(source, SpeechSegment(TimeRange(10_000, 12_000))) }
                }
            }
        }
        assertEquals("ASR inference failed", error.message)
        assertTrue(modelClosed)
        assertTrue(audio.closed)
    }

    private class TestAudio : PcmAudioSource {
        override val sampleCount = 192_000L
        var closed = false
        override suspend fun read(startSample: Long, maxSamples: Int): FloatArray {
            check(!closed)
            require(startSample >= 0 && maxSamples > 0)
            val count = (sampleCount - startSample).coerceIn(0, maxSamples.toLong()).toInt()
            return FloatArray(count)
        }
        override fun close() { closed = true }
    }

    /** Запускает контрактные тесты без библиотеки корутин, допуская приостановку выполнения. */
    private fun <T> runSuspend(block: suspend () -> T): T {
        val completed = CountDownLatch(1)
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                outcome = result
                completed.countDown()
            }
        })
        check(completed.await(5, TimeUnit.SECONDS)) { "Contract test timed out" }
        return checkNotNull(outcome).getOrThrow()
    }
}
