package org.cfm.catscriptor.domain.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingProgressTest {
    private fun stages(vararg pairs: Pair<ProcessingStage, StageStatus>) = pairs.map { StageProgress(it.first, it.second) }

    @Test fun nothingStartedIsZeroAndAllDoneIsOne() {
        assertEquals(0f, ProcessingProgress.fraction(emptyList(), 10_000), 1e-6f)
        val done = ProcessingStage.entries.map { StageProgress(it, StageStatus.Done(1)) }
        assertEquals(1f, ProcessingProgress.fraction(done, 10_000), 1e-6f)
    }

    @Test fun llmStageProgressFollowsProcessedAudio() {
        val before = stages(
            ProcessingStage.PREPARE_AUDIO to StageStatus.Done(1),
            ProcessingStage.DETECT_SPEECH to StageStatus.Done(1),
            ProcessingStage.DIARIZE to StageStatus.Done(1),
            ProcessingStage.TRANSCRIBE to StageStatus.Done(1),
        )
        val quarter = ProcessingProgress.fraction(before + StageProgress(ProcessingStage.CORRECT_TEXT, StageStatus.Running(0, 2_500)), 10_000)
        val half = ProcessingProgress.fraction(before + StageProgress(ProcessingStage.CORRECT_TEXT, StageStatus.Running(0, 5_000)), 10_000)
        assertEquals(0.2f + 0.8f * 0.25f, quarter, 1e-4f)
        assertEquals(0.2f + 0.8f * 0.5f, half, 1e-4f)
    }

    @Test fun skippedStageCountsAsComplete() {
        val all = ProcessingStage.entries.map {
            StageProgress(it, if (it == ProcessingStage.CORRECT_TEXT) StageStatus.Skipped else StageStatus.Done(1))
        }
        assertEquals(1f, ProcessingProgress.fraction(all, 1_000), 1e-6f)
    }

    @Test fun remainingTimeIsExtrapolatedOnlyWhenEstimateIsMeaningful() {
        assertNull(ProcessingProgress.remainingMs(0.01f, 60_000))
        assertNull(ProcessingProgress.remainingMs(0.5f, 1_000))
        assertNull(ProcessingProgress.remainingMs(1f, 60_000))
        assertEquals(30_000L, ProcessingProgress.remainingMs(0.5f, 30_000))
    }

    @Test fun onlyLlmCorrectionIsOptional() {
        assertTrue(ProcessingStage.CORRECT_TEXT.isOptional)
        ProcessingStage.entries.filter { it != ProcessingStage.CORRECT_TEXT }.forEach { assertFalse(it.isOptional) }
    }
}
