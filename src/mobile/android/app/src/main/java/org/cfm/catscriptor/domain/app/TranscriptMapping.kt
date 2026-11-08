package org.cfm.catscriptor.domain.app

import org.cfm.catscriptor.domain.pipeline.Transcript
import org.cfm.catscriptor.domain.pipeline.Utterance

/** Номер N из `speaker_N`; для нестандартного id возвращает null. */
fun speakerNumber(speakerId: String): Int? = speakerId.substringAfterLast('_').toIntOrNull()

/** Метаданные записи, которых нет в результате пайплайна. */
data class TranscriptMetadata(
    val recordingId: String,
    val title: String,
    val createdAtEpochMs: Long,
    val durationMs: Long,
    val speakerNames: Map<String, String> = emptyMap(),
    val bookmarksMs: List<Long> = emptyList(),
)

/**
 * Перевод результата пайплайна в модель экрана. Id реплики строится из её позиции и исходного
 * таймкода ASR: правка текста и смена спикера его не меняют, поэтому выделение и прокрутка
 * в UI не «прыгают» после редактирования.
 */
fun Transcript.toDocument(metadata: TranscriptMetadata): TranscriptDocument {
    val lines = transcript.mapIndexed { index, utterance ->
        TranscriptLine(
            id = lineId(index, utterance.startMs),
            speakerId = utterance.speakerId,
            startMs = utterance.startMs,
            endMs = utterance.endMs,
            text = utterance.text,
        )
    }
    val speakers = computeSpeakers(lines, metadata.speakerNames)
    return TranscriptDocument(
        recordingId = metadata.recordingId,
        title = metadata.title,
        createdAtEpochMs = metadata.createdAtEpochMs,
        durationMs = metadata.durationMs,
        speakers = speakers,
        lines = lines,
        bookmarksMs = metadata.bookmarksMs,
    )
}

fun lineId(index: Int, startMs: Long): String = "line-$index-$startMs"

/**
 * Доля спикера = его суммарное время реплик / суммарное время всех реплик. Знаменатель — время
 * речи, а не длительность файла: иначе доли не складываются в 100 % из-за пауз.
 * Спикеры идут по возрастанию N, чтобы чипы и цвета были стабильны.
 */
fun computeSpeakers(lines: List<TranscriptLine>, names: Map<String, String> = emptyMap()): List<SpeakerInfo> {
    val durations = LinkedHashMap<String, Long>()
    for (line in lines) {
        durations[line.speakerId] = (durations[line.speakerId] ?: 0L) + (line.endMs - line.startMs).coerceAtLeast(0)
    }
    val total = durations.values.sum()
    return durations.keys
        .sortedWith(compareBy<String> { speakerNumber(it) ?: Int.MAX_VALUE }.thenBy { it })
        .map { id ->
            SpeakerInfo(
                id = id,
                displayName = names[id],
                shareFraction = if (total > 0) durations.getValue(id).toFloat() / total else 0f,
            )
        }
}

/** Пересчёт долей после смены спикера у реплики; заданные пользователем имена сохраняются. */
fun TranscriptDocument.withRecomputedSpeakers(): TranscriptDocument {
    val names = speakers.mapNotNull { speaker -> speaker.displayName?.let { speaker.id to it } }.toMap()
    val recomputed = computeSpeakers(lines, names)
    // Спикер без реплик остаётся в списке с нулевой долей: его можно снова выбрать при правке.
    val missing = speakers.filter { old -> recomputed.none { it.id == old.id } }.map { it.copy(shareFraction = 0f) }
    return copy(speakers = (recomputed + missing).sortedBy { speakerNumber(it.id) ?: Int.MAX_VALUE })
}

/**
 * Обратный перевод для строгого JSON-экспорта без синтетических слов:
 * текст (включая пустой) и абсолютные интервалы реплик сохраняются без изменений.
 */
fun TranscriptDocument.toPipelineTranscript(): Transcript {
    require(asrDraft == null) { "ASR draft cannot be exported" }
    return Transcript(
    lines.sortedBy { it.startMs }.map { line ->
        Utterance(
            speakerId = line.speakerId,
            startMs = line.startMs,
            endMs = line.endMs,
            text = line.text,
        )
    },
)
}
