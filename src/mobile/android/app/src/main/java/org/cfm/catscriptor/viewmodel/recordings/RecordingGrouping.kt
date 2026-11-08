package org.cfm.catscriptor.viewmodel.recordings

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.cfm.catscriptor.domain.app.RecordingSummary

/** Заголовок группы в списке записей; подпись к нему выбирает UI. */
sealed interface RecordingGroupKey {
    data object Today : RecordingGroupKey
    data object Yesterday : RecordingGroupKey

    /** [month] от 1 до 12. Год показывается, только если он не текущий. */
    data class Month(val year: Int, val month: Int, val isCurrentYear: Boolean) : RecordingGroupKey
}

data class RecordingGroup(val key: RecordingGroupKey, val items: List<RecordingSummary>)

/**
 * Группы «Сегодня», «Вчера», затем по месяцам, от новых к старым. Записи текущего месяца старше
 * вчерашнего дня попадают в группу месяца, как в макете («Ноябрь» при сегодняшнем 7 ноября).
 * Время передаётся явно, чтобы границы суток проверялись тестом без системных часов.
 */
fun groupRecordings(items: List<RecordingSummary>, nowEpochMs: Long, zone: ZoneId): List<RecordingGroup> {
    val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
    val yesterday = today.minusDays(1)
    val groups = LinkedHashMap<RecordingGroupKey, MutableList<RecordingSummary>>()
    for (item in items.sortedByDescending { it.createdAtEpochMs }) {
        val date = Instant.ofEpochMilli(item.createdAtEpochMs).atZone(zone).toLocalDate()
        groups.getOrPut(keyFor(date, today, yesterday)) { mutableListOf() }.add(item)
    }
    return groups.map { (key, list) -> RecordingGroup(key, list) }
}

private fun keyFor(date: LocalDate, today: LocalDate, yesterday: LocalDate): RecordingGroupKey = when {
    // Запись «из будущего» (часы сдвинулись) считаем сегодняшней, а не отдельным месяцем.
    !date.isBefore(today) -> RecordingGroupKey.Today
    date == yesterday -> RecordingGroupKey.Yesterday
    else -> RecordingGroupKey.Month(date.year, date.monthValue, isCurrentYear = date.year == today.year)
}
