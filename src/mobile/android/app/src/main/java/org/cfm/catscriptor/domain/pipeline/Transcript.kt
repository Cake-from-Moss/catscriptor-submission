package org.cfm.catscriptor.domain.pipeline

/**
 * Реплика спикера с абсолютным интервалом аудио, без обязательной пословной привязки.
 * Локальная LLM исправляет [text], сохраняя спикера и исходные границы реплики.
 */
data class Utterance(
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
) {
    init {
        requireSpeakerId(speakerId)
        TimeRange(startMs, endMs)
    }
}

/** Реплики отсортированы по началу; экспортируются только speakerId/startMs/endMs/text. */
data class Transcript(val transcript: List<Utterance>) {
    init {
        require(transcript.zipWithNext().all { (a, b) -> a.startMs <= b.startMs }) {
            "Utterances must be ordered by start time; overlaps are allowed"
        }
    }
}
