package org.cfm.catscriptor.viewmodel.transcript

import org.cfm.catscriptor.domain.app.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptNavigationTest {
    private val lines = listOf(
        TranscriptLine("a", "speaker_1", 1_000, 2_000, "Добрый день"),
        TranscriptLine("b", "speaker_1", 3_000, 4_000, "Всё работает"),
        TranscriptLine("c", "speaker_2", 5_000, 6_000, "ЕЩЁ вопрос"),
    )

    @Test
    fun speedCyclesInDesignOrder() {
        val visited = generateSequence(1f) { nextSpeed(it) }.take(6).toList()
        assertEquals(listOf(1f, 1.25f, 1.5f, 2f, 0.75f, 1f), visited)
        assertEquals(1f, nextSpeed(3f))
    }

    @Test
    fun currentLineStaysOnPreviousDuringPause() {
        assertEquals(-1, currentLineIndex(lines, 500))
        assertEquals(0, currentLineIndex(lines, 1_000))
        assertEquals(0, currentLineIndex(lines, 2_500))
        assertEquals(2, currentLineIndex(lines, 60_000))
        assertEquals(-1, currentLineIndex(emptyList(), 1_000))
    }

    @Test
    fun previousAndNextLineJumps() {
        assertEquals(1_000L, previousLineStart(lines, 3_500))
        assertEquals(0L, previousLineStart(lines, 1_500))
        assertEquals(5_000L, nextLineStart(lines, 3_500))
        assertEquals(1_000L, nextLineStart(lines, 0))
        assertNull(nextLineStart(lines, 5_500))
    }

    @Test
    fun searchIgnoresCaseAndYo() {
        assertEquals(listOf("c"), searchMatches(lines, "еще"))
        assertEquals(listOf("a"), searchMatches(lines, "  ДЕНЬ "))
        assertEquals(emptyList<String>(), searchMatches(lines, "   "))
        assertEquals("ещё".length, normalizeForSearch("ЕЩЁ").length)
    }

    @Test
    fun trackSegmentsMergeSameSpeakerAndReachTheEnd() {
        assertEquals(
            listOf(TrackSegment("speaker_1", 1_000, 5_000), TrackSegment("speaker_2", 5_000, 10_000)),
            trackSegments(lines, durationMs = 10_000),
        )
        assertEquals(emptyList<TrackSegment>(), trackSegments(emptyList(), 10_000))
    }
}
