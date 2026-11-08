package org.cfm.catscriptor.domain.diarization

import org.cfm.catscriptor.domain.pipeline.SpeakerSegment
import org.cfm.catscriptor.domain.pipeline.SpeechSegment
import org.cfm.catscriptor.domain.pipeline.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerTurnPlannerTest {
    private val planner = SpeakerTurnPlanner()
    private fun spk(id: Int, start: Long, end: Long) = SpeakerSegment("speaker_$id", TimeRange(start, end))
    private fun vad(start: Long, end: Long) = SpeechSegment(TimeRange(start, end))
    private fun List<SpeakerSegment>.compact() = map { Triple(it.speakerId, it.time.startMs, it.time.endMs) }

    @Test fun silenceAndNoSpeakersGiveNoTurns() {
        assertTrue(planner.plan(listOf(spk(1, 0, 1000)), emptyList()).isEmpty())
        assertTrue(planner.plan(emptyList(), listOf(vad(0, 1000))).isEmpty())
    }

    @Test fun speakerIntervalsAreClippedToVadSpeech() {
        val turns = planner.plan(listOf(spk(1, 0, 10_000)), listOf(vad(2_000, 4_000)))
        assertEquals(listOf(Triple("speaker_1", 1_850L, 4_150L)), turns.compact())
    }

    @Test fun oneVadSegmentWithTwoSpeakersIsSplitIntoTurns() {
        val turns = planner.plan(listOf(spk(1, 1_000, 4_000), spk(2, 4_000, 8_000)), listOf(vad(1_000, 8_000)))
        assertEquals(listOf(Triple("speaker_1", 1_000L, 4_000L), Triple("speaker_2", 4_000L, 8_000L)), turns.compact())
    }

    @Test fun alternationKeepsOrder() {
        val turns = planner.plan(
            listOf(spk(1, 0, 2_000), spk(2, 2_500, 4_500), spk(1, 5_000, 7_000)), listOf(vad(0, 7_000)))
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_1"), turns.map { it.speakerId })
    }

    @Test fun overlapIsOwnedByIncumbentAndNeverDuplicated() {
        // speaker_2 перебивает внутри хода speaker_1 и продолжает после его окончания.
        val turns = planner.plan(listOf(spk(1, 0, 6_000), spk(2, 4_000, 9_000)), listOf(vad(0, 9_000)))
        assertEquals(listOf(Triple("speaker_1", 0L, 6_000L), Triple("speaker_2", 6_000L, 9_000L)), turns.compact())
        // Ходы не пересекаются: одно и то же аудио распознаётся один раз.
        turns.zipWithNext().forEach { (a, b) -> assertTrue(a.time.endMs <= b.time.startMs) }
    }

    @Test fun shortInterjectionInsideTurnIsAbsorbed() {
        val turns = planner.plan(listOf(spk(1, 0, 6_000), spk(2, 2_000, 2_800)), listOf(vad(0, 6_000)))
        assertEquals(listOf(Triple("speaker_1", 0L, 6_000L)), turns.compact())
    }

    @Test fun simultaneousStartPrefersLowerNumber() {
        val turns = planner.plan(listOf(spk(2, 0, 3_000), spk(1, 0, 3_000)), listOf(vad(0, 3_000)))
        assertEquals(listOf(Triple("speaker_1", 0L, 3_000L)), turns.compact())
    }

    @Test fun sameSpeakerShortPauseIsMergedLongPauseIsNot() {
        val merged = planner.plan(listOf(spk(1, 0, 1_000), spk(1, 1_300, 2_500)), listOf(vad(0, 2_500)))
        assertEquals(listOf(Triple("speaker_1", 0L, 2_500L)), merged.compact())
        val separate = planner.plan(listOf(spk(1, 0, 1_000), spk(1, 3_000, 4_000)), listOf(vad(0, 1_000), vad(3_000, 4_000)))
        assertEquals(2, separate.size)
    }

    @Test fun shortTurnBetweenDifferentSpeakersKeepsItsAudio() {
        // Короткий ход speaker_2 вплотную к speaker_3: аудио не выбрасывается, а уходит соседу.
        val turns = planner.plan(
            listOf(spk(1, 0, 2_000), spk(2, 2_000, 2_200), spk(3, 2_200, 4_000)),
            listOf(vad(0, 4_000)),
        )
        assertEquals(listOf(Triple("speaker_1", 0L, 2_200L), Triple("speaker_3", 2_200L, 4_000L)), turns.compact())
    }

    @Test fun shortFirstTurnJoinsNextSpeaker() {
        val turns = planner.plan(listOf(spk(2, 0, 200), spk(1, 200, 3_000)), listOf(vad(0, 3_000)))
        assertEquals(listOf(Triple("speaker_1", 0L, 3_000L)), turns.compact())
    }

    @Test fun twoTouchingShortTurnsTogetherLongEnoughAreKept() {
        // 200 + 200 мс вплотную, вокруг паузы: вместе ход длиннее minTurnMs и не выбрасывается.
        val turns = planner.plan(
            listOf(spk(1, 0, 2_000), spk(2, 5_000, 5_200), spk(1, 5_200, 5_400), spk(1, 9_000, 11_000)),
            listOf(vad(0, 2_000), vad(5_000, 5_400), vad(9_000, 11_000)),
        )
        assertEquals(listOf(Triple("speaker_1", 0L, 2_000L), Triple("speaker_1", 5_000L, 5_400L), Triple("speaker_1", 9_000L, 11_000L)), turns.compact())
    }

    @Test fun isolatedShortTurnIsStillDropped() {
        val turns = planner.plan(
            listOf(spk(1, 0, 2_000), spk(2, 5_000, 5_200), spk(1, 9_000, 11_000)),
            listOf(vad(0, 2_000), vad(5_000, 5_200), vad(9_000, 11_000)),
        )
        assertEquals(listOf("speaker_1", "speaker_1"), turns.map { it.speakerId })
    }

    @Test fun tooShortTurnsAreDropped() {
        val turns = planner.plan(listOf(spk(1, 0, 2_000), spk(2, 2_100, 2_250), spk(1, 2_300, 4_000)), listOf(vad(0, 4_000)))
        assertEquals(listOf(Triple("speaker_1", 0L, 4_000L)), turns.compact())
    }
}
