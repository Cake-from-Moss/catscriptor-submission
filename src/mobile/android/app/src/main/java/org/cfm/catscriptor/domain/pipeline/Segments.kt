package org.cfm.catscriptor.domain.pipeline

/** Полуоткрытый интервал в миллисекундах от начала исходной записи. */
data class TimeRange(val startMs: Long, val endMs: Long) {
    init {
        require(startMs >= 0 && endMs > startMs) { "Expected 0 <= startMs < endMs" }
    }

    fun contains(other: TimeRange): Boolean =
        startMs <= other.startMs && endMs >= other.endMs
}

/** Экспериментальная пословная привязка; не является обязательной частью результата ASR. */
data class TimedWord(val text: String, val time: TimeRange) {
    init {
        require(text.isNotBlank()) { "A timed word must contain text" }
    }
}

/** Речь, обнаруженная VAD; временные метки отсчитываются от начала записи. */
data class SpeechSegment(val time: TimeRange)

/** Текст и абсолютный интервал обработанного фрагмента; пустой текст допустим для тишины. */
data class AsrSegment(val time: TimeRange, val text: String)

/** Интервалы могут перекрываться, в том числе у разных спикеров. */
data class SpeakerSegment(val speakerId: String, val time: TimeRange) {
    init {
        requireSpeakerId(speakerId)
    }
}

internal fun requireSpeakerId(value: String) {
    require(Regex("speaker_[1-9][0-9]*").matches(value)) { "Expected speaker_N with N >= 1" }
}
