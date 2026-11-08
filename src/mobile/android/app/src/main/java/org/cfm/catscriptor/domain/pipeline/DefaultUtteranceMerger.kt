package org.cfm.catscriptor.domain.pipeline

/** Для фрагмента ASR нет ни одного интервала диаризации с допустимым пересечением. */
class SpeakerAssignmentException(val segment: TimeRange) :
    IllegalStateException("Не найден говорящий для фрагмента ${segment.startMs}..${segment.endMs} мс")

/**
 * Параметры объединения реплик; значения не подбирались на оценочных записях.
 *
 * @property maxGapMs наибольшая пауза между репликами одного спикера, при которой они склеиваются.
 * @property maxUtteranceMs предельная длительность склеенной реплики: речь не сливается в один блок на минуты.
 * @property minInterjectionMs длительность речи другого спикера в паузе, начиная с которой склейка запрещена.
 * @property minOverlapMs минимальное пересечение ASR-фрагмента с интервалами спикера для назначения.
 */
data class MergeSettings(
    val maxGapMs: Long = 1_000,
    val maxUtteranceMs: Long = 60_000,
    val minInterjectionMs: Long = 200,
    val minOverlapMs: Long = 1,
) {
    init {
        require(maxGapMs >= 0 && maxUtteranceMs > 0 && minInterjectionMs > 0 && minOverlapMs > 0)
    }
}

/**
 * Назначает спикера целому ASR-фрагменту по суммарной длительности пересечения и склеивает
 * соседние реплики одного спикера. Текст никогда не делится по длине или «позициям слов».
 *
 * Правила назначения: побеждает спикер с наибольшим пересечением (объединение его интервалов,
 * без двойного счёта); при равенстве — с большим IoU (его интервал точнее совпадает с фрагментом),
 * затем тот, чьё пересечение началось раньше, затем меньший номер `speaker_N`. Если пересечения нет, выбрасывается [SpeakerAssignmentException]: произвольный
 * `speaker_1` не подставляется.
 *
 * Правила склейки: реплики одного спикера соединяются, только если между ними в упорядоченном
 * списке нет реплик других спикеров, пауза не больше [MergeSettings.maxGapMs], в паузе нет заметной
 * речи другого спикера и результат не длиннее [MergeSettings.maxUtteranceMs].
 */
class DefaultUtteranceMerger(private val settings: MergeSettings = MergeSettings()) : UtteranceMerger {
    override suspend fun merge(asr: List<AsrSegment>, speakers: List<SpeakerSegment>): Transcript {
        val byspeaker = speakers.groupBy { it.speakerId }
            .mapValues { (_, list) -> unionOf(list.map { it.time }) }

        val assigned = asr.asSequence()
            .filter { it.text.isNotBlank() }
            .map { Utterance(assign(it.time, byspeaker), it.time.startMs, it.time.endMs, it.text.trim()) }
            .sortedWith(compareBy({ it.startMs }, { it.endMs }, { speakerNumber(it.speakerId) }))
            .toList()

        val result = ArrayList<Utterance>(assigned.size)
        val lastIndex = HashMap<String, Int>()
        for (utterance in assigned) {
            val previousIndex = lastIndex[utterance.speakerId]
            val previous = previousIndex?.let { result[it] }
            if (previous != null && previousIndex == result.lastIndex && canJoin(previous, utterance, byspeaker)) {
                result[previousIndex] = previous.copy(
                    endMs = maxOf(previous.endMs, utterance.endMs),
                    text = previous.text + " " + utterance.text,
                )
            } else {
                result += utterance
                lastIndex[utterance.speakerId] = result.lastIndex
            }
        }
        return Transcript(result)
    }

    private fun assign(time: TimeRange, byspeaker: Map<String, List<TimeRange>>): String {
        var best: String? = null
        var bestOverlap = 0L
        var bestIou = 0.0
        var bestFirst = Long.MAX_VALUE
        val duration = time.endMs - time.startMs
        for ((speaker, ranges) in byspeaker) {
            var overlap = 0L
            var own = 0L
            var first = Long.MAX_VALUE
            for (range in ranges) {
                val start = maxOf(range.startMs, time.startMs)
                val end = minOf(range.endMs, time.endMs)
                if (end > start) {
                    overlap += end - start
                    own += range.endMs - range.startMs
                    first = minOf(first, start)
                }
            }
            if (overlap < settings.minOverlapMs) continue
            // При равном пересечении точнее тот, чей интервал целиком лежит в ASR-фрагменте (IoU), а не чужой длинный ход.
            val iou = overlap.toDouble() / (duration + own - overlap)
            val better = best == null || overlap > bestOverlap ||
                (overlap == bestOverlap && (iou > bestIou ||
                    (iou == bestIou && (first < bestFirst ||
                        (first == bestFirst && speakerNumber(speaker) < speakerNumber(best))))))
            if (better) {
                best = speaker; bestOverlap = overlap; bestIou = iou; bestFirst = first
            }
        }
        return best ?: throw SpeakerAssignmentException(time)
    }

    private fun canJoin(a: Utterance, b: Utterance, byspeaker: Map<String, List<TimeRange>>): Boolean {
        val gap = b.startMs - a.endMs
        if (gap > settings.maxGapMs) return false
        if (maxOf(a.endMs, b.endMs) - a.startMs > settings.maxUtteranceMs) return false
        if (gap > 0) {
            for ((speaker, ranges) in byspeaker) {
                if (speaker == a.speakerId) continue
                val inGap = ranges.sumOf { maxOf(0L, minOf(it.endMs, b.startMs) - maxOf(it.startMs, a.endMs)) }
                if (inGap >= settings.minInterjectionMs) return false
            }
        }
        return true
    }

    private fun unionOf(ranges: List<TimeRange>): List<TimeRange> {
        val sorted = ranges.sortedBy { it.startMs }
        val union = ArrayList<TimeRange>(sorted.size)
        for (range in sorted) {
            val last = union.lastOrNull()
            if (last != null && range.startMs <= last.endMs) {
                if (range.endMs > last.endMs) union[union.lastIndex] = TimeRange(last.startMs, range.endMs)
            } else {
                union += range
            }
        }
        return union
    }

    private fun speakerNumber(id: String): Int = id.substringAfter('_').toInt()
}
