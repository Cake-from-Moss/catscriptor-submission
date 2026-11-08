package org.cfm.catscriptor.domain.diarization

import org.cfm.catscriptor.domain.pipeline.SpeakerSegment
import org.cfm.catscriptor.domain.pipeline.SpeechSegment
import org.cfm.catscriptor.domain.pipeline.TimeRange

/**
 * Параметры уточнения ходов; значения начальные и не подбирались на оценочных записях.
 *
 * @property speechToleranceMs на сколько расширяется речь VAD, прежде чем обрезать по ней интервалы диаризации.
 * @property minTurnMs более короткий ход не распознаётся отдельно: на долях секунды модель ухудшает
 *   результат. Его аудио присоединяется к соседнему ходу без паузы, иначе ход отбрасывается.
 * @property sameSpeakerGapMs паузы одного спикера не длиннее этой склеиваются в один ход.
 */
data class TurnPlannerSettings(
    val speechToleranceMs: Long = 150,
    val minTurnMs: Long = 300,
    val sameSpeakerGapMs: Long = 400,
) {
    init {
        require(speechToleranceMs >= 0 && minTurnMs > 0 && sameSpeakerGapMs >= 0)
    }
}

/**
 * Превращает интервалы диаризации в непересекающиеся ходы, каждый из которых распознаётся
 * ASR ровно один раз и принадлежит одному спикеру.
 *
 * 1. Интервалы обрезаются по расширенной речи VAD: тишина не получает спикера.
 * 2. Развёртка по времени. Где активен один спикер, владелец он. Где активны несколько
 *    (перебивание), участок остаётся за спикером, который уже говорил непосредственно перед ним;
 *    если речь начиналась одновременно, владеет тот, чей интервал начался раньше (затем меньший номер).
 *    Так одно и то же аудио не распознаётся дважды и текст не дублируется.
 * 3. Соседние ходы одного спикера с короткой паузой склеиваются. Слишком короткий ход
 *    присоединяется к вплотную примыкающему соседу (сначала к предыдущему): его аудио — часто
 *    короткое «да» или конец слова после неточной границы — иначе вообще не попало бы в ASR.
 *    Отбрасывается только короткий ход, отделённый паузой от обоих соседей.
 *
 * Ограничение: без разделения источников реплика перебившего внутри чужого хода остаётся в тексте
 * владельца хода, а его собственным ходом становится только непересекающаяся часть речи.
 */
class SpeakerTurnPlanner(private val settings: TurnPlannerSettings = TurnPlannerSettings()) {
    fun plan(speakers: List<SpeakerSegment>, speech: List<SpeechSegment>): List<SpeakerSegment> {
        if (speakers.isEmpty() || speech.isEmpty()) return emptyList()
        val allowed = dilate(speech.map { it.time })
        val clipped = speakers.flatMap { segment -> clip(segment, allowed) }
        if (clipped.isEmpty()) return emptyList()

        var turns = sweep(clipped)
        // Отбрасывание коротких ходов может сблизить два хода одного спикера: повторяем склейку.
        turns = mergeSame(turns)
        turns = absorbShort(turns)
        return mergeSame(turns)
    }

    private fun dilate(ranges: List<TimeRange>): List<TimeRange> {
        val sorted = ranges.sortedBy { it.startMs }
        val result = ArrayList<TimeRange>()
        for (range in sorted) {
            val start = maxOf(0L, range.startMs - settings.speechToleranceMs)
            val end = range.endMs + settings.speechToleranceMs
            val last = result.lastOrNull()
            if (last != null && start <= last.endMs) {
                if (end > last.endMs) result[result.lastIndex] = TimeRange(last.startMs, end)
            } else {
                result += TimeRange(start, end)
            }
        }
        return result
    }

    private fun clip(segment: SpeakerSegment, allowed: List<TimeRange>): List<SpeakerSegment> =
        allowed.mapNotNull { range ->
            val start = maxOf(range.startMs, segment.time.startMs)
            val end = minOf(range.endMs, segment.time.endMs)
            if (end > start) SpeakerSegment(segment.speakerId, TimeRange(start, end)) else null
        }

    private fun sweep(segments: List<SpeakerSegment>): List<SpeakerSegment> {
        val points = segments.flatMap { listOf(it.time.startMs, it.time.endMs) }.distinct().sorted()
        val result = ArrayList<SpeakerSegment>()
        var previousOwner: String? = null
        var previousEnd = -1L
        for (i in 0 until points.lastIndex) {
            val start = points[i]
            val end = points[i + 1]
            val active = segments.filter { it.time.startMs <= start && it.time.endMs >= end }
                .map { it.speakerId }.distinct()
            if (active.isEmpty()) {
                previousOwner = null
                continue
            }
            val owner = when {
                active.size == 1 -> active[0]
                previousOwner != null && previousEnd == start && previousOwner in active -> previousOwner
                else -> active.minWithOrNull(
                    compareBy<String>({ activeStart(segments, it, start) }, { speakerNumber(it) }),
                )!!
            }
            val last = result.lastOrNull()
            if (last != null && last.speakerId == owner && last.time.endMs == start) {
                result[result.lastIndex] = SpeakerSegment(owner, TimeRange(last.time.startMs, end))
            } else {
                result += SpeakerSegment(owner, TimeRange(start, end))
            }
            previousOwner = owner
            previousEnd = end
        }
        return result
    }

    /** Начало непрерывного присутствия спикера, покрывающего момент [at]. */
    private fun activeStart(segments: List<SpeakerSegment>, speaker: String, at: Long): Long =
        segments.filter { it.speakerId == speaker && it.time.startMs <= at && it.time.endMs > at }
            .minOf { it.time.startMs }

    private fun absorbShort(turns: List<SpeakerSegment>): List<SpeakerSegment> {
        val result = ArrayList<SpeakerSegment>(turns.size)
        // Начало следующего хода, к которому присоединяется короткий ход без предыдущего соседа.
        var carryStart: Long? = null
        for ((index, turn) in turns.withIndex()) {
            val time = carryStart?.let { TimeRange(it, turn.time.endMs) } ?: turn.time
            carryStart = null
            // Длительность с учётом присоединённого предыдущего короткого хода.
            if (time.endMs - time.startMs >= settings.minTurnMs) {
                result += SpeakerSegment(turn.speakerId, time)
                continue
            }
            val previous = result.lastOrNull()
            val next = turns.getOrNull(index + 1)
            when {
                previous != null && time.startMs - previous.time.endMs <= settings.sameSpeakerGapMs ->
                    result[result.lastIndex] = SpeakerSegment(previous.speakerId, TimeRange(previous.time.startMs, time.endMs))
                next != null && next.time.startMs - time.endMs <= settings.sameSpeakerGapMs ->
                    carryStart = time.startMs
                else -> Unit
            }
        }
        return result
    }

    private fun mergeSame(turns: List<SpeakerSegment>): List<SpeakerSegment> {
        val result = ArrayList<SpeakerSegment>(turns.size)
        for (turn in turns) {
            val last = result.lastOrNull()
            if (last != null && last.speakerId == turn.speakerId &&
                turn.time.startMs - last.time.endMs <= settings.sameSpeakerGapMs
            ) {
                result[result.lastIndex] = SpeakerSegment(last.speakerId, TimeRange(last.time.startMs, turn.time.endMs))
            } else {
                result += turn
            }
        }
        return result
    }

    private fun speakerNumber(id: String): Int = id.substringAfter('_').toInt()
}
