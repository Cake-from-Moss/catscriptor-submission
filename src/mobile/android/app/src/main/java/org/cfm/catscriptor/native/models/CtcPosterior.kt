package org.cfm.catscriptor.native.models

/** Занятые токеном кадры CTC-пути; это ещё не доказанные фонетические границы слова. */
data class CtcTokenFrames(val tokenId: Int, val startFrame: Int, val endFrameExclusive: Int)

/**
 * Ограниченный диагностический снимок настоящего ONNX output, без повторного инференса.
 * Не используется как TimedWord: CTC может сосредоточить вероятность в короткой эмиссии.
 */
class CtcPosterior internal constructor(
    val frameCount: Int,
    val vocabularySize: Int,
    val frameShiftMs: Int,
    val blankId: Int,
    private val scores: FloatArray,
) {
    init {
        require(frameCount in 1..625 && vocabularySize in 2..256)
        require(frameShiftMs > 0 && blankId in 0 until vocabularySize)
        require(scores.size == frameCount * vocabularySize && scores.all { it.isFinite() })
    }

    /** Viterbi для CTC-графа blank, token, blank; повтор буквы требует промежуточного blank. */
    fun align(tokenIds: List<Int>, checkCancelled: () -> Unit = {}): List<CtcTokenFrames> {
        require(tokenIds.all { it in 0 until vocabularySize && it != blankId })
        require(tokenIds.size <= frameCount)
        checkCancelled()
        if (tokenIds.isEmpty()) return emptyList()
        val repeatedPairs = tokenIds.zipWithNext().count { (left, right) -> left == right }
        val minimumFrames = tokenIds.size + repeatedPairs
        check(minimumFrames <= frameCount) {
            "Не существует CTC-пути: требуется минимум $minimumFrames кадров, доступно $frameCount"
        }
        val states = 2 * tokenIds.size + 1
        fun label(state: Int) = if (state % 2 == 0) blankId else tokenIds[state / 2]
        var previous = DoubleArray(states) { Double.NEGATIVE_INFINITY }
        var current = DoubleArray(states)
        val trace = ByteArray(frameCount * states)
        previous[0] = scores[blankId].toDouble()
        previous[1] = scores[tokenIds[0]].toDouble()
        for (frame in 1 until frameCount) {
            checkCancelled()
            for (state in 0 until states) {
                var best = previous[state]
                var step = 0
                if (state >= 1 && previous[state - 1] > best) {
                    best = previous[state - 1]
                    step = 1
                }
                if (state >= 2 && state % 2 == 1 && label(state) != label(state - 2) && previous[state - 2] > best) {
                    best = previous[state - 2]
                    step = 2
                }
                current[state] = best + scores[frame * vocabularySize + label(state)]
                trace[frame * states + state] = step.toByte()
            }
            val swap = previous
            previous = current
            current = swap
        }
        var state = if (previous[states - 1] > previous[states - 2]) states - 1 else states - 2
        check(previous[state].isFinite()) { "Не существует CTC-пути для заданных токенов" }
        val starts = IntArray(tokenIds.size) { frameCount }
        val ends = IntArray(tokenIds.size)
        for (frame in frameCount - 1 downTo 0) {
            checkCancelled()
            if (state % 2 == 1) {
                val token = state / 2
                starts[token] = frame
                ends[token] = maxOf(ends[token], frame + 1)
            }
            if (frame > 0) state -= trace[frame * states + state]
        }
        return tokenIds.indices.map { index ->
            check(starts[index] < ends[index]) { "Токену не назначен акустический кадр" }
            CtcTokenFrames(tokenIds[index], starts[index], ends[index])
        }
    }

    companion object {
        internal const val OPTION = "catscriptor.ctc.frames.v1"

        internal fun parse(value: String): CtcPosterior {
            check(value.startsWith("v1 ")) {
                "CTC logits недоступны: требуется диагностическая сборка sherpa, не официальный AAR"
            }
            require(value.length <= 1_000_000)
            val parts = value.trim().split(Regex("\\s+"))
            require(parts.size >= 5)
            val frames = parts[1].toInt()
            val vocab = parts[2].toInt()
            require(frames in 1..625 && vocab == 34 && parts[3] == "40" && parts[4] == "33")
            require(parts.size == 5 + frames * vocab)
            return CtcPosterior(frames, vocab, 40, 33, FloatArray(frames * vocab) { parts[it + 5].toFloat() })
        }
    }
}
