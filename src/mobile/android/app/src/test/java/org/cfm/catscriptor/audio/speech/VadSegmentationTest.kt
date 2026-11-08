package org.cfm.catscriptor.audio.speech

import org.cfm.catscriptor.domain.pipeline.TimeRange
import org.junit.Assert.*
import org.junit.Test

class VadSegmentationTest {
    private fun VadSegmentation.frames(count: Int, probability: Float) = repeat(count) { accept(probability, 512) }

    @Test fun silenceAndShortClicksAreNotSpeech() {
        val vad = VadSegmentation(SileroVadSettings())
        vad.frames(1000, 0f)
        vad.frames(3, 1f)
        vad.frames(100, 0f)
        assertTrue(vad.finish().isEmpty())
    }

    @Test fun boundariesUseAbsoluteOffsetsAndPadding() {
        val vad = VadSegmentation(SileroVadSettings())
        vad.frames(100, 0f)
        vad.frames(10, 1f)
        vad.frames(20, 0f)
        assertEquals(listOf(TimeRange(3050, 3670)), vad.finish().map { it.time })
    }

    @Test fun pauseCreatesTwoOrderedRanges() {
        val vad = VadSegmentation(SileroVadSettings())
        vad.frames(10, 1f)
        vad.frames(40, 0f)
        vad.frames(10, 1f)
        vad.frames(20, 0f)
        assertEquals(listOf(TimeRange(0, 470), TimeRange(1450, 2070)), vad.finish().map { it.time })
    }

    @Test fun paddingMergesTouchingRangesAndDoesNotExceedEof() {
        val vad = VadSegmentation(SileroVadSettings(minSilenceMs = 160))
        vad.frames(10, 1f)
        vad.frames(6, 0f)
        vad.frames(10, 1f)
        assertEquals(listOf(TimeRange(0, 832)), vad.finish().map { it.time })
    }

    @Test fun finalPartialFrameAndHysteresisKeepShortWord() {
        val vad = VadSegmentation(SileroVadSettings(speechPadMs = 0))
        vad.frames(4, 0.9f)
        vad.frames(2, 0.4f)
        vad.accept(0.6f, 17)
        assertEquals(listOf(TimeRange(0, 194)), vad.finish().map { it.time })
    }

    @Test fun continuousSpeechDoesNotUseNativeSixtySecondBuffer() {
        val vad = VadSegmentation(SileroVadSettings())
        vad.frames(200_000, 0.9f)
        assertEquals(listOf(TimeRange(0, 6_400_000)), vad.finish().map { it.time })
    }

    @Test fun sampleCounterDoesNotOverflowAfterThirtyEightHours() {
        val vad = VadSegmentation(SileroVadSettings())
        vad.frames(4_300_000, 0.9f)
        assertEquals(listOf(TimeRange(0, 137_600_000)), vad.finish().map { it.time })
    }

    @Test fun hysteresisMustAllowDigitalSilenceToEndSpeech() {
        assertThrows(IllegalArgumentException::class.java) { SileroVadSettings(threshold = 0.15f) }
        val vad = VadSegmentation(SileroVadSettings(threshold = 0.151f, speechPadMs = 0))
        vad.frames(4, 0.9f)
        vad.frames(30, 0f)
        assertEquals(listOf(TimeRange(0, 128)), vad.finish().map { it.time })
    }

    @Test(expected = IllegalArgumentException::class) fun nonFiniteProbabilityIsRejected() {
        VadSegmentation(SileroVadSettings()).accept(Float.NaN, 512)
    }
}
