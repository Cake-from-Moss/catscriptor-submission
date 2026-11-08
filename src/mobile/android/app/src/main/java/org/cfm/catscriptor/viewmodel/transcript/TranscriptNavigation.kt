package org.cfm.catscriptor.viewmodel.transcript

import org.cfm.catscriptor.domain.app.TranscriptLine

/** Цикл кнопки скорости: 1.0× → 1.25× → 1.5× → 2× → 0.75× → 1.0×. */
val PLAYBACK_SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)

/** Неизвестная скорость (например, выставленная плеером) переводит цикл в начало. */
fun nextSpeed(current: Float): Float {
    val index = PLAYBACK_SPEEDS.indexOfFirst { it == current }
    return if (index < 0) PLAYBACK_SPEEDS.first() else PLAYBACK_SPEEDS[(index + 1) % PLAYBACK_SPEEDS.size]
}

/**
 * Текущая реплика — последняя начавшаяся к [positionMs]. В паузе между репликами подсветка
 * остаётся на предыдущей: так она не мигает на каждом молчании. До первой реплики — -1.
 * [lines] отсортированы по startMs, поэтому хватает бинарного поиска.
 */
fun currentLineIndex(lines: List<TranscriptLine>, positionMs: Long): Int {
    var low = 0
    var high = lines.lastIndex
    var found = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lines[mid].startMs <= positionMs) {
            found = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return found
}

/** Начало предыдущей реплики относительно текущей; до первой — начало записи. */
fun previousLineStart(lines: List<TranscriptLine>, positionMs: Long): Long {
    val current = currentLineIndex(lines, positionMs)
    return if (current <= 0) 0L else lines[current - 1].startMs
}

/** Начало следующей реплики или null, если текущая последняя. */
fun nextLineStart(lines: List<TranscriptLine>, positionMs: Long): Long? =
    lines.getOrNull(currentLineIndex(lines, positionMs) + 1)?.startMs

/** Id реплик, где встречается [query], без учёта регистра и разницы «ё»/«е». */
fun searchMatches(lines: List<TranscriptLine>, query: String): List<String> {
    val needle = normalizeForSearch(query.trim())
    if (needle.isEmpty()) return emptyList()
    return lines.filter { normalizeForSearch(it.text).contains(needle) }.map { it.id }
}

/** Длина строки не меняется, поэтому индексы совпадений годятся для подсветки исходного текста. */
fun normalizeForSearch(text: String): String = buildString(text.length) {
    for (char in text) {
        val lower = char.lowercaseChar()
        append(if (lower == 'ё') 'е' else lower)
    }
}

/** Отрезок дорожки плеера: подряд идущие реплики одного спикера сливаются в один отрезок. */
data class TrackSegment(val speakerId: String, val startMs: Long, val endMs: Long)

/**
 * Отрезок реплики тянется до начала следующей, чтобы паузы не рвали дорожку на мелкие куски;
 * последний отрезок идёт до конца записи. Тишина до первой реплики остаётся пустой.
 */
fun trackSegments(lines: List<TranscriptLine>, durationMs: Long): List<TrackSegment> {
    val sorted = lines.sortedBy { it.startMs }
    val end = maxOf(durationMs, sorted.maxOfOrNull { it.endMs } ?: 0L)
    val segments = mutableListOf<TrackSegment>()
    sorted.forEachIndexed { index, line ->
        val segmentEnd = sorted.getOrNull(index + 1)?.startMs ?: end
        if (segmentEnd <= line.startMs) return@forEachIndexed
        val last = segments.lastOrNull()
        if (last != null && last.speakerId == line.speakerId) {
            segments[segments.lastIndex] = last.copy(endMs = segmentEnd)
        } else {
            segments += TrackSegment(line.speakerId, line.startMs, segmentEnd)
        }
    }
    return segments
}
