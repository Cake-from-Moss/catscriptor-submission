package org.cfm.catscriptor.viewmodel.recordings

import java.time.LocalDateTime
import java.time.ZoneId
import org.cfm.catscriptor.domain.app.RecordingSource
import org.cfm.catscriptor.domain.app.RecordingStatus
import org.cfm.catscriptor.domain.app.RecordingSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingGroupingTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val now = epoch(2026, 11, 7, 12, 0)

    private fun epoch(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun summary(id: String, createdAt: Long) = RecordingSummary(
        id = id, title = id, source = RecordingSource.FILE, createdAtEpochMs = createdAt, durationMs = 1_000,
        speakerCount = 1, previewSpeakerId = null, previewSpeakerName = null, previewText = null,
        status = RecordingStatus.Ready,
    )

    @Test
    fun groupsTodayYesterdayAndMonthsNewestFirst() {
        val items = listOf(
            summary("nov1", epoch(2026, 11, 1, 9, 0)),
            summary("today-early", epoch(2026, 11, 7, 0, 0)),
            summary("yesterday-late", epoch(2026, 11, 6, 23, 59)),
            summary("today", epoch(2026, 11, 7, 10, 42)),
            summary("october", epoch(2026, 10, 31, 23, 59)),
            summary("last-year", epoch(2025, 11, 20, 8, 0)),
            summary("nov5", epoch(2026, 11, 5, 9, 0)),
        )
        val groups = groupRecordings(items, now, zone)
        assertEquals(
            listOf(
                RecordingGroupKey.Today,
                RecordingGroupKey.Yesterday,
                RecordingGroupKey.Month(2026, 11, isCurrentYear = true),
                RecordingGroupKey.Month(2026, 10, isCurrentYear = true),
                RecordingGroupKey.Month(2025, 11, isCurrentYear = false),
            ),
            groups.map { it.key },
        )
        assertEquals(listOf("today", "today-early"), groups[0].items.map { it.id })
        assertEquals(listOf("yesterday-late"), groups[1].items.map { it.id })
        assertEquals(listOf("nov5", "nov1"), groups[2].items.map { it.id })
    }

    @Test
    fun dayBoundaryFollowsZoneNotUtc() {
        // 23:30 по Москве 6 ноября — это 20:30 UTC, но для пользователя это всё ещё «вчера».
        val groups = groupRecordings(listOf(summary("x", epoch(2026, 11, 6, 23, 30))), now, zone)
        assertEquals(RecordingGroupKey.Yesterday, groups.single().key)
    }

    @Test
    fun futureTimestampCountsAsToday() {
        val groups = groupRecordings(listOf(summary("x", epoch(2026, 11, 9, 8, 0))), now, zone)
        assertEquals(RecordingGroupKey.Today, groups.single().key)
    }

    @Test
    fun emptyInputGivesNoGroups() {
        assertEquals(emptyList<RecordingGroup>(), groupRecordings(emptyList(), now, zone))
    }
}
