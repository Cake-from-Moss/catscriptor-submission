package org.cfm.catscriptor.recording

/** Ограниченная сегментация Silero-вероятностей; все позиции относятся к сохранённому PCM. */
internal class LivePcmSegmenter(
    private val probability: (FloatArray) -> Float,
    private val resetVad: () -> Unit,
    private val onPhrase: (Long, FloatArray) -> Unit,
) {
    private val window = FloatArray(512)
    private val phrase = FloatArray(8 * 16000)
    private var filled = 0
    private var expected = -1L
    private var windowStart = 0L
    private var phraseStart = 0L
    private var phraseSize = 0
    private var silence = 0
    private var speaking = false

    fun accept(start: Long, data: FloatArray) {
        if (start < 0 || expected != start) {
            // При перегрузке не склеиваем PCM по разные стороны пропуска.
            flush()
            filled = 0
            resetVad()
        }
        if (start < 0) { expected = -1; return }
        var offset = 0
        while (offset < data.size) {
            if (filled == 0) windowStart = start + offset
            val count = minOf(window.size - filled, data.size - offset)
            data.copyInto(window, filled, offset, offset + count)
            filled += count
            offset += count
            if (filled == window.size) {
                val speech = probability(window) >= 0.5f && window.any { it != 0f }
                if (speech && !speaking) { speaking = true; phraseStart = windowStart }
                if (speaking) {
                    window.copyInto(phrase, phraseSize)
                    phraseSize += window.size
                    silence = if (speech) 0 else silence + window.size
                    if (silence >= 8192 || phraseSize + window.size > phrase.size) flush()
                }
                filled = 0
            }
        }
        expected = start + data.size
    }

    fun flush() {
        if (phraseSize >= 4096) onPhrase(phraseStart, phrase.copyOf(phraseSize))
        phraseSize = 0
        silence = 0
        speaking = false
    }
}
