package org.cfm.catscriptor.audio.background

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.cfm.catscriptor.domain.pipeline.TextCorrectionBackend
import org.cfm.catscriptor.domain.pipeline.Utterance
import org.junit.Assert.*
import org.junit.Test

class TextCorrectionRunnerTest {
    private val source = listOf(Utterance("speaker_1", 20, 900, "привет"), Utterance("speaker_2", 1000, 2000, "да"))
    private val seed = AudioProcessingState(AudioProcessingPhase.COMPLETED, transcript = source, speakerDraft = source)

    @Test fun correctsOnlyTextAndClosesModelBeforeCompletion() = runTest {
        var closed = false
        val snapshots = mutableListOf<AudioProcessingState>()
        val runner = TextCorrectionRunner({ object : TextCorrectionBackend {
            override suspend fun correct(utterance: Utterance) = if (utterance == source.first()) "Привет!" else ""
            override fun close() { closed = true }
        } }, { 100 })
        val result = runner.run(seed, snapshots::add)
        assertTrue(closed)
        assertEquals(listOf(source[0].copy(text = "Привет!"), source[1]), result.transcript)
        assertEquals(source, result.speakerDraft)
        assertEquals(AudioProcessingPhase.COMPLETED, result.phase)
        assertTrue(snapshots.all { it.transcript == source && it.phase == AudioProcessingPhase.RUNNING })
    }

    @Test fun errorAndCancellationCloseModelWithoutPublishingPartialReplacement() = runTest {
        for (failure in listOf(IllegalStateException("Ошибка"), CancellationException("Отмена"))) {
            var closed = false
            val snapshots = mutableListOf<AudioProcessingState>()
            val runner = TextCorrectionRunner({ object : TextCorrectionBackend {
                override suspend fun correct(utterance: Utterance): String {
                    if (utterance == source[1]) throw failure
                    return "Привет!"
                }
                override fun close() { closed = true }
            } }, { 100 })
            try { runner.run(seed, snapshots::add); fail("Ожидалась ошибка") } catch (actual: Exception) {
                assertSame(failure, actual)
            }
            assertTrue(closed)
            assertTrue(snapshots.all { it.transcript == source })
        }
    }
}
