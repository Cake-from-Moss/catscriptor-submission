package org.cfm.catscriptor.domain.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TranscriptTest {
    private fun utterance(start: Long = 1250, end: Long = 2000) = Utterance(
        "speaker_1", start, end, "Привет!",
    )

    @Test
    fun emptyTranscriptIsValid() {
        assertEquals(emptyList<Utterance>(), Transcript(emptyList()).transcript)
    }

    @Test
    fun correctionPreservesSpeakerAndAbsoluteTimesWithoutWords() {
        val original = utterance()
        val corrected = original.copy(text = "Добрый день!")
        assertEquals(original.speakerId, corrected.speakerId)
        assertEquals(1250L, corrected.startMs)
        assertEquals(2000L, corrected.endMs)
        assertEquals("Добрый день!", corrected.text)
    }

    @Test
    fun invalidIntervalsAreRejected() {
        listOf(-1L to 10L, 10L to 10L, 20L to 10L).forEach { (start, end) ->
            assertThrows(IllegalArgumentException::class.java) { TimeRange(start, end) }
            assertThrows(IllegalArgumentException::class.java) { utterance(start, end) }
        }
    }

    @Test
    fun largeRecordingOffsetsRemainLongIntegers() {
        val start = Int.MAX_VALUE.toLong() + 1000
        assertEquals(start, utterance(start, start + 500).startMs)
    }

    @Test
    fun invalidSpeakerIdsAreRejected() {
        listOf("", "Alice", "speaker_0", "speaker_-1", "speaker_01").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { utterance().copy(speakerId = id) }
            assertThrows(IllegalArgumentException::class.java) {
                SpeakerSegment(id, TimeRange(0, 100))
            }
        }
    }

    @Test
    fun overlappingUtterancesAreAllowedButUnsortedOnesAreRejected() {
        val first = utterance(0, 1000)
        val second = utterance(500, 1500).copy(speakerId = "speaker_2")
        assertEquals(2, Transcript(listOf(first, second)).transcript.size)
        assertThrows(IllegalArgumentException::class.java) { Transcript(listOf(second, first)) }
    }

    @Test
    fun recognizedFragmentRetainsTextAndAbsoluteIntervalWithoutWords() {
        val range = TimeRange(10_000, 12_000)
        val recognized = AsrSegment(range, "Привет, кот!")
        assertEquals(range, recognized.time)
        assertEquals("Привет, кот!", recognized.text)
    }

    @Test
    fun silenceAllowsEmptyOrBlankText() {
        listOf("", " ").forEach { text ->
            val range = TimeRange(10_000, 11_000)
            assertEquals(text, AsrSegment(range, text).text)
            assertEquals(range, AsrSegment(range, text).time)
        }
    }
}
