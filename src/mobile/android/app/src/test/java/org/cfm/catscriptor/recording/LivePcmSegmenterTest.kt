package org.cfm.catscriptor.recording

import org.junit.Assert.*
import org.junit.Test

class LivePcmSegmenterTest {
    @Test fun silenceDoesNotProduceTextCandidates() {
        var phrases = 0
        val segmenter = LivePcmSegmenter({ 0f }, {}, { _, _ -> phrases++ })
        repeat(1000) { segmenter.accept(it * 512L, FloatArray(512)) }
        segmenter.flush()
        assertEquals(0, phrases)
    }

    @Test fun continuousSpeechHasBoundedChunksAndSampleTimestamps() {
        val phrases = mutableListOf<Pair<Long, Int>>()
        val segmenter = LivePcmSegmenter({ 1f }, {}, { start, pcm -> phrases += start to pcm.size })
        repeat(1000) { segmenter.accept(it * 512L, FloatArray(512) { 0.2f }) }
        segmenter.flush()
        assertTrue(phrases.size > 1)
        assertTrue(phrases.all { it.second in 4096..128000 })
        assertEquals(512000, phrases.sumOf { it.second })
        assertEquals(0L, phrases.first().first)
        phrases.zipWithNext().forEach { (a, b) -> assertEquals(a.first + a.second, b.first) }
    }

    @Test fun gapsAndPauseNeverInventContinuousAudio() {
        val starts = mutableListOf<Long>()
        val segmenter = LivePcmSegmenter({ 1f }, {}, { start, _ -> starts += start })
        segmenter.accept(0, FloatArray(4096) { 1f })
        segmenter.accept(16000, FloatArray(4096) { 1f })
        segmenter.accept(-1, floatArrayOf())
        segmenter.accept(20096, FloatArray(4096) { 1f })
        segmenter.flush()
        assertEquals(listOf(0L, 16000L, 20096L), starts)
    }
}
