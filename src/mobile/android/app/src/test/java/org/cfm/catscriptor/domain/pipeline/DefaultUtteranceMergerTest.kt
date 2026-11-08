package org.cfm.catscriptor.domain.pipeline

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultUtteranceMergerTest {
    private val merger = DefaultUtteranceMerger()

    private fun asr(start: Long, end: Long, text: String) = AsrSegment(TimeRange(start, end), text)
    private fun spk(id: Int, start: Long, end: Long) = SpeakerSegment("speaker_$id", TimeRange(start, end))
    private fun merge(a: List<AsrSegment>, s: List<SpeakerSegment>, m: UtteranceMerger = merger) = runBlocking { m.merge(a, s).transcript }

    @Test fun singleSpeaker() {
        val result = merge(listOf(asr(1000, 3000, "Привет")), listOf(spk(1, 900, 3100)))
        assertEquals(listOf(Utterance("speaker_1", 1000, 3000, "Привет")), result)
    }

    @Test fun twoSpeakersKeepAbsoluteOffsets() {
        val result = merge(
            listOf(asr(1_250, 2_860, "Привет, как дела?"), asr(3_050, 4_730, "Всё отлично.")),
            listOf(spk(1, 1_200, 2_900), spk(2, 3_000, 4_800)),
        )
        assertEquals(listOf("speaker_1" to 1_250L, "speaker_2" to 3_050L), result.map { it.speakerId to it.startMs })
        assertEquals(4_730L, result.last().endMs)
    }

    @Test fun threeSpeakersAndAlternationAreNotMerged() {
        val result = merge(
            listOf(asr(0, 1000, "a"), asr(1100, 2000, "b"), asr(2100, 3000, "c"), asr(3100, 4000, "d")),
            listOf(spk(1, 0, 1000), spk(2, 1100, 2000), spk(3, 2100, 3000), spk(1, 3100, 4000)),
        )
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_3", "speaker_1"), result.map { it.speakerId })
    }

    @Test fun adjacentSameSpeakerMergedWithinGap() {
        val result = merge(
            listOf(asr(0, 1000, "один"), asr(1500, 2500, "два")),
            listOf(spk(1, 0, 2500)),
        )
        assertEquals(listOf(Utterance("speaker_1", 0, 2500, "один два")), result)
    }

    @Test fun gapLargerThanLimitKeepsSeparate() {
        val result = merge(listOf(asr(0, 1000, "один"), asr(5000, 6000, "два")), listOf(spk(1, 0, 6000)))
        assertEquals(2, result.size)
    }

    @Test fun otherSpeakerBetweenForbidsMerge() {
        val result = merge(
            listOf(asr(0, 1000, "а"), asr(1100, 1500, "б"), asr(1600, 2600, "в")),
            listOf(spk(1, 0, 1000), spk(2, 1100, 1500), spk(1, 1600, 2600)),
        )
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_1"), result.map { it.speakerId })
    }

    @Test fun interjectionInsideGapForbidsMergeEvenWithoutAsrText() {
        val result = merge(
            listOf(asr(0, 1000, "а"), asr(1900, 2800, "б")),
            listOf(spk(1, 0, 1000), spk(2, 1200, 1700), spk(1, 1900, 2800)),
        )
        assertEquals(2, result.size)
    }

    @Test fun maxUtteranceDurationLimitsMerge() {
        val small = DefaultUtteranceMerger(MergeSettings(maxUtteranceMs = 2_200))
        val result = merge(
            listOf(asr(0, 1000, "а"), asr(1100, 2100, "б"), asr(2200, 3200, "в")),
            listOf(spk(1, 0, 3200)), small,
        )
        assertEquals(2, result.size)
        assertEquals("а б", result[0].text)
    }

    @Test fun equalOverlapIsDeterministic() {
        val a = listOf(asr(1000, 3000, "текст"))
        val s = listOf(spk(2, 1000, 2000), spk(1, 2000, 3000))
        // Равное пересечение и равный IoU: выигрывает тот, чьё пересечение началось раньше.
        assertEquals("speaker_2", merge(a, s)[0].speakerId)
        // Полностью равны по началу: меньший номер.
        assertEquals("speaker_1", merge(a, listOf(spk(2, 1000, 2000), spk(1, 1000, 2000)))[0].speakerId)
        assertEquals(merge(a, s), merge(a, s.reversed()))
    }

    @Test fun longestOverlapWinsAndTextIsNotSplit() {
        val result = merge(listOf(asr(0, 4000, "одна фраза целиком")), listOf(spk(1, 0, 1000), spk(2, 1000, 4000)))
        assertEquals(listOf(Utterance("speaker_2", 0, 4000, "одна фраза целиком")), result)
    }

    @Test fun sameSpeakerSegmentsDoNotDoubleCountOverlap() {
        // speaker_1 дублирует интервал дважды: без объединения он выиграл бы у speaker_2.
        val result = merge(listOf(asr(0, 1000, "т")),
            listOf(spk(1, 0, 400), spk(1, 0, 400), spk(1, 0, 400), spk(2, 400, 1000)))
        assertEquals("speaker_2", result[0].speakerId)
    }

    @Test fun noOverlapFailsExplicitly() {
        val error = assertThrows(SpeakerAssignmentException::class.java) {
            merge(listOf(asr(5000, 6000, "речь")), listOf(spk(1, 0, 1000)))
        }
        assertEquals(TimeRange(5000, 6000), error.segment)
    }

    @Test fun emptyAsrAndSilenceProduceNothing() {
        assertTrue(merge(emptyList(), listOf(spk(1, 0, 1000))).isEmpty())
        assertTrue(merge(listOf(asr(0, 1000, "  ")), emptyList()).isEmpty())
        assertTrue(merge(emptyList(), emptyList()).isEmpty())
    }

    @Test fun overlappingDifferentSpeakersAreKeptWithoutDuplicates() {
        val result = merge(
            listOf(asr(0, 3000, "длинная реплика"), asr(2000, 2800, "да")),
            listOf(spk(1, 0, 3000), spk(2, 2000, 2800)),
        )
        assertEquals(2, result.size)
        assertEquals(listOf("длинная реплика", "да"), result.map { it.text })
        assertEquals(listOf(0L, 2000L), result.map { it.startMs })
    }

    @Test fun textIsNeitherLostNorDuplicated() {
        val segments = (0 until 10).map { asr(it * 1500L, it * 1500L + 1000, "слово$it") }
        val result = merge(segments, listOf(spk(1, 0, 15_000)), DefaultUtteranceMerger(MergeSettings(maxUtteranceMs = 4_000)))
        assertEquals(segments.joinToString(" ") { it.text }, result.joinToString(" ") { it.text })
    }

    @Test fun outputIsSortedByStart() {
        val result = merge(listOf(asr(5000, 6000, "b"), asr(0, 1000, "a")), listOf(spk(1, 0, 1000), spk(2, 5000, 6000)))
        assertEquals(listOf(0L, 5000L), result.map { it.startMs })
    }
}
