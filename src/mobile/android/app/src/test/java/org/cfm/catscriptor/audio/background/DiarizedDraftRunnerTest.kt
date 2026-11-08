package org.cfm.catscriptor.audio.background

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.cfm.catscriptor.domain.app.ProcessingStage
import org.cfm.catscriptor.domain.app.StageStatus
import org.cfm.catscriptor.domain.pipeline.*
import org.junit.Assert.*
import org.junit.Test

class DiarizedDraftRunnerTest {
    private class Fixture(
        val speech: List<SpeechSegment>,
        val speakers: List<SpeakerSegment>,
        val asrFailure: Exception? = null,
        val diarizationFailure: Exception? = null,
        val correctorFailure: Exception? = null,
        /** Ответы корректора по startMs реплики; null — вернуть текст без изменений. */
        val corrections: Map<Long, String> = emptyMap(),
        val textCorrectionEnabled: Boolean = true,
    ) {
        val events = mutableListOf<String>()
        val states = mutableListOf<AudioProcessingState>()
        val transcribed = mutableListOf<TimeRange>()
        val corrected = mutableListOf<Long>()
        val pcm = object : PcmAudioSource {
            override val sampleCount = 160_000L
            override suspend fun read(startSample: Long, maxSamples: Int) = FloatArray(maxSamples)
            override fun close() { events += "pcm.close" }
        }
        val runner = DiarizedDraftRunner(
            decoder = object : AudioDecoder {
                override suspend fun decode(input: AudioInput): PcmAudioSource { events += "decode"; return pcm }
            },
            vadFactory = {
                events += "vad.create"
                object : VoiceActivityDetector {
                    override suspend fun detect(audio: PcmAudioSource) = speech
                    override fun close() { events += "vad.close" }
                }
            },
            diarizerFactory = {
                events += "diarizer.create"
                object : DiarizationBackend {
                    override suspend fun diarize(audio: PcmAudioSource, speech: List<SpeechSegment>): List<SpeakerSegment> {
                        events += "diarizer.diarize"
                        diarizationFailure?.let { throw it }
                        return speakers
                    }
                    override fun close() { events += "diarizer.close" }
                }
            },
            asrFactory = {
                events += "asr.create"
                object : TranscriptionBackend {
                    override suspend fun transcribe(audio: PcmAudioSource, segment: SpeechSegment): AsrSegment {
                        events += "asr.transcribe"
                        asrFailure?.let { throw it }
                        transcribed += segment.time
                        return AsrSegment(segment.time, "реплика ${segment.time.startMs}")
                    }
                    override fun close() { events += "asr.close" }
                }
            },
            merger = DefaultUtteranceMerger(),
            correctorFactory = {
                events += "corrector.create"
                object : TextCorrectionBackend {
                    override suspend fun correct(utterance: Utterance): String {
                        events += "corrector.correct"
                        correctorFailure?.let { throw it }
                        corrected += utterance.startMs
                        return corrections[utterance.startMs] ?: utterance.text
                    }
                    override fun close() { events += "corrector.close" }
                }
            },
            clock = { 100L },
            textCorrectionEnabled = textCorrectionEnabled,
        )
        suspend fun run() = runner.run(AudioInput("content://test/audio"), states::add)
    }

    private fun spk(id: Int, start: Long, end: Long) = SpeakerSegment("speaker_$id", TimeRange(start, end))

    @Test fun disabledCorrectionCompletesWithRawSpeakerTranscriptWithoutLoadingLlm() = runBlocking {
        val fixture = Fixture(
            speech = listOf(SpeechSegment(TimeRange(1_000, 3_000))),
            speakers = listOf(spk(1, 1_000, 3_000)),
            textCorrectionEnabled = false,
        )
        val result = fixture.run()
        assertEquals(AudioProcessingPhase.COMPLETED, result.phase)
        assertEquals(result.speakerDraft, result.transcript)
        assertFalse(fixture.events.contains("corrector.create"))
        assertEquals(StageStatus.Skipped, result.stages.single { it.stage == ProcessingStage.CORRECT_TEXT }.status)
    }

    @Test fun modelsAreLoadedOneAtATimeAndClosedBeforeTheNextStage() = runBlocking {
        val fixture = Fixture(
            speech = listOf(SpeechSegment(TimeRange(1_000, 6_000))),
            speakers = listOf(spk(1, 1_000, 3_000), spk(2, 3_000, 6_000)),
            corrections = mapOf(1_000L to "Исправлено 1000.", 3_000L to "Исправлено 3000."),
        )
        val result = fixture.run()
        assertEquals(listOf("decode", "vad.create", "vad.close", "diarizer.create", "diarizer.diarize", "diarizer.close",
            "asr.create", "asr.transcribe", "asr.transcribe", "asr.close",
            "pcm.close", "corrector.create", "corrector.correct", "corrector.correct", "corrector.close"), fixture.events)
        // Один VAD-сегмент с двумя говорящими даёт два отдельных ASR-вызова по ходам, без деления текста.
        assertEquals(listOf(TimeRange(1_000, 3_000), TimeRange(3_000, 6_000)), fixture.transcribed)
        assertEquals(listOf("speaker_1", "speaker_2"), result.speakerDraft!!.map { it.speakerId })
        assertEquals(listOf(1_000L, 3_000L), result.speakerDraft!!.map { it.startMs })
        assertEquals(AudioProcessingPhase.COMPLETED, result.phase)
        assertTrue(result.stages.all { it.status is StageStatus.Done })
        // Финальный транскрипт: исправленный текст, спикеры и таймкоды исходные.
        assertEquals(listOf("Исправлено 1000.", "Исправлено 3000."), result.transcript!!.map { it.text })
        assertEquals(listOf("speaker_1", "speaker_2"), result.transcript.map { it.speakerId })
        assertEquals(listOf(1_000L, 3_000L), result.transcript.map { it.startMs })
        assertEquals(listOf(3_000L, 6_000L), result.transcript.map { it.endMs })
    }

    @Test fun silenceNeverCreatesDiarizerAsrOrCorrector() = runBlocking {
        val fixture = Fixture(emptyList(), emptyList())
        val result = fixture.run()
        assertEquals(emptyList<Utterance>(), result.speakerDraft)
        assertEquals(emptyList<Utterance>(), result.transcript)
        assertFalse(fixture.events.contains("diarizer.create"))
        assertFalse(fixture.events.contains("asr.create"))
        assertFalse(fixture.events.contains("corrector.create"))
        assertEquals("pcm.close", fixture.events.last())
    }

    @Test fun speechWithoutDetectedSpeakersDoesNotInventOne() = runBlocking {
        val fixture = Fixture(listOf(SpeechSegment(TimeRange(0, 5_000))), emptyList())
        val result = fixture.run()
        assertEquals(emptyList<Utterance>(), result.speakerDraft)
        assertEquals(emptyList<Utterance>(), result.transcript)
        assertFalse(fixture.events.contains("asr.create"))
        assertFalse(fixture.events.contains("corrector.create"))
    }

    @Test fun diarizationFailureClosesEverythingAndNeverStartsAsr() {
        val fixture = Fixture(listOf(SpeechSegment(TimeRange(0, 5_000))), emptyList(),
            diarizationFailure = IllegalStateException("native"))
        assertThrows(IllegalStateException::class.java) { runBlocking { fixture.run() } }
        assertEquals(listOf("decode", "vad.create", "vad.close", "diarizer.create", "diarizer.diarize",
            "diarizer.close", "pcm.close"), fixture.events)
        assertTrue(fixture.states.last().stages.any { it.stage == ProcessingStage.DIARIZE && it.status is StageStatus.Failed })
        // Текст исключения может содержать URI документа, поэтому в состояние для UI он не попадает.
        assertEquals(StageStatus.Failed(null), fixture.states.last().stages.single { it.stage == ProcessingStage.DIARIZE }.status)
    }

    @Test fun asrCancellationClosesAsrAndPcm() {
        val fixture = Fixture(listOf(SpeechSegment(TimeRange(0, 5_000))), listOf(spk(1, 0, 5_000)),
            asrFailure = CancellationException("cancelled"))
        assertThrows(CancellationException::class.java) { runBlocking { fixture.run() } }
        assertEquals(listOf("asr.create", "asr.transcribe", "asr.close", "pcm.close"), fixture.events.takeLast(4))
        assertFalse(fixture.events.contains("corrector.create"))
    }

    @Test fun correctionFailureClosesCorrectorAndFailsStage() {
        val fixture = Fixture(listOf(SpeechSegment(TimeRange(0, 5_000))), listOf(spk(1, 0, 5_000)),
            correctorFailure = IllegalStateException("llm native"))
        assertThrows(IllegalStateException::class.java) { runBlocking { fixture.run() } }
        assertEquals(listOf("pcm.close", "corrector.create", "corrector.correct", "corrector.close"),
            fixture.events.takeLast(4))
        assertTrue(fixture.states.last().stages.any { it.stage == ProcessingStage.CORRECT_TEXT && it.status is StageStatus.Failed })
        assertNull(fixture.states.last().transcript)
    }

    @Test fun blankCorrectionKeepsRecognizedText() = runBlocking {
        val fixture = Fixture(listOf(SpeechSegment(TimeRange(0, 5_000))), listOf(spk(1, 0, 5_000)),
            corrections = mapOf(0L to "   "))
        val result = fixture.run()
        assertEquals(listOf("реплика 0"), result.transcript!!.map { it.text })
        assertEquals(listOf("speaker_1"), result.transcript.map { it.speakerId })
    }

    @Test fun draftIsPublishedOnlyAfterMergeAndTranscriptOnlyAfterCorrection() = runBlocking {
        val fixture = Fixture(listOf(SpeechSegment(TimeRange(0, 5_000))), listOf(spk(1, 0, 5_000)))
        fixture.run()
        // Реплики со спикерами появляются только с этапа LLM (после слияния), финальный текст — только в конце.
        assertTrue(fixture.states.all { it.speakerDraft == null || it.stage == ProcessingStage.CORRECT_TEXT })
        assertTrue(fixture.states.all { it.transcript == null || it.phase == AudioProcessingPhase.COMPLETED })
    }

    @Test fun correctionFailureKeepsSpeakerDraftForSkipping() {
        val fixture = Fixture(listOf(SpeechSegment(TimeRange(0, 5_000))), listOf(spk(1, 0, 5_000)),
            correctorFailure = IllegalStateException("llm native"))
        assertThrows(IllegalStateException::class.java) { runBlocking { fixture.run() } }
        // Без этого «Пропустить этап» после ошибки LLM было бы не из чего собрать.
        assertEquals(listOf("speaker_1" to "реплика 0"),
            fixture.states.last().speakerDraft!!.map { it.speakerId to it.text })
    }
}
