package org.cfm.catscriptor.domain.pipeline

/**
 * Пословное согласование ответа LLM с исходным ASR-текстом (issue #7).
 *
 * Глобальные проверки [CorrectionGuard] отсекают явный мусор, но пропускают правку целиком:
 * при пороге «35% слов можно заменить» модель успевала выбросить слово или подменить
 * неразборчивое слово другим, правдоподобным по смыслу. Здесь итоговый текст собирается так,
 * чтобы последовательность слов осталась от ASR, а от LLM бралось только безопасное:
 *
 * - пунктуация и регистр — из ответа LLM;
 * - замена слова — только «орфографическая» (малое посимвольное расстояние, см. [isSpellingFix]);
 *   иначе остаётся слово ASR;
 * - склейка/разбиение без изменения букв («по этому» → «поэтому», «незнаю» → «не знаю»);
 * - слово, которое LLM удалила, возвращается на место;
 * - слово, которое LLM добавила, выбрасывается (знаки препинания вокруг него сохраняются).
 *
 * Таким образом LLM не может ни потерять распознанное слово, ни выдумать новое: WER меняется
 * только за счёт близких по написанию исправлений.
 */
class CorrectionReconciler(private val config: ReconcilerConfig = ReconcilerConfig()) {

    /** Итог согласования и счётчики для диагностики. */
    data class Result(
        val text: String,
        val acceptedFixes: Int,
        val rejectedSubstitutions: Int,
        val restoredWords: Int,
        val droppedInsertions: Int,
    )

    /** Слово ответа LLM вместе с разделителем перед ним (пробелы, пунктуация). */
    private class Piece(val gapBefore: String, val raw: String) {
        val norm: String = normalize(raw)
    }

    private enum class Op { MATCH, SUB, DEL, INS, MERGE, SPLIT }

    fun reconcile(original: String, candidate: String): Result {
        val source = WORD.findAll(original).map { it.value }.toList()
        val pieces = ArrayList<Piece>()
        var cursor = 0
        for (match in WORD.findAll(candidate)) {
            pieces += Piece(candidate.substring(cursor, match.range.first), match.value)
            cursor = match.range.last + 1
        }
        val tail = candidate.substring(cursor)
        val src = source.map(::normalize)
        val ops = align(src, pieces.map { it.norm })

        val out = StringBuilder()
        var pendingGap = ""
        var fixes = 0
        var rejected = 0
        var restored = 0
        var dropped = 0
        var i = 0
        var j = 0
        // Разделитель слова j, уже отданный восстановленному слову (см. Op.DEL).
        var consumed = -1
        fun gapOf(index: Int) = if (index == consumed) " " else pieces[index].gapBefore
        fun emit(gap: String, word: String) {
            val merged = mergeGaps(pendingGap, gap)
            pendingGap = ""
            out.append(if (out.isEmpty()) merged.trimStart() else merged.ifEmpty { " " })
            out.append(word)
        }
        for (op in ops) {
            when (op) {
                Op.MATCH -> {
                    emit(gapOf(j), pieces[j].raw); i++; j++
                }
                Op.SUB -> {
                    val piece = pieces[j]
                    if (isSpellingFix(src[i], piece.norm)) {
                        emit(gapOf(j), piece.raw); fixes++
                    } else {
                        // Регистр слова LLM не переносим: «Барсик» вместо «он» дал бы «Он» посреди
                        // предложения. Начало предложения поднимает capitalizeSentences.
                        emit(gapOf(j), source[i]); rejected++
                    }
                    i++; j++
                }
                Op.MERGE -> {
                    emit(gapOf(j), pieces[j].raw); i += 2; j++
                }
                Op.SPLIT -> {
                    emit(gapOf(j), pieces[j].raw)
                    emit(gapOf(j + 1), pieces[j + 1].raw)
                    i++; j += 2
                }
                Op.DEL -> {
                    // Слово, выброшенное LLM, возвращаем как распознал ASR. Знак, который LLM
                    // поставила после предыдущего слова («Кот, дома»), остаётся после него:
                    // восстановленное слово забирает разделитель следующего («Кот, сидит дома»).
                    val takeGap = out.isNotEmpty() && j in 1 until pieces.size && consumed != j
                    val gap = if (takeGap) pieces[j].gapBefore.also { consumed = j } else " "
                    emit(gap, source[i]); restored++; i++
                }
                Op.INS -> {
                    // Выдуманное слово выбрасываем, но его знаки препинания не теряем.
                    pendingGap = mergeGaps(pendingGap, gapOf(j)); dropped++; j++
                }
            }
        }
        out.append(mergeGaps(pendingGap, tail).trimEnd())
        return Result(capitalizeSentences(out.toString().trim()), fixes, rejected, restored, dropped)
    }

    /**
     * Замена допустима как исправление ошибки распознавания, только если слова близки по написанию:
     * GigaAM ошибается в отдельных буквах («вакзал» → «вокзал»), а не меняет слово целиком.
     * Короткие слова, числа и отрицания не меняются никогда: «и»/«а», «не»/«на» меняют смысл.
     */
    internal fun isSpellingFix(source: String, candidate: String): Boolean {
        if (source == candidate) return true
        if (source.any(Char::isDigit) || candidate.any(Char::isDigit)) return false
        if (source in config.protectedWords || candidate in config.protectedWords) return false
        val longest = maxOf(source.length, candidate.length)
        if (minOf(source.length, candidate.length) < config.minFixableLength) return false
        val distance = charDistance(source, candidate)
        return distance <= config.maxCharEditsFor(longest)
    }

    /**
     * Выравнивание нормализованных слов. Стоимости: совпадение 0, склейка/разбиение без изменения
     * букв 0, замена 1, вставка и удаление 1. При равенстве предпочитается замена (пунктуация
     * LLM остаётся при соответствующем слове), затем удаление.
     */
    private fun align(a: List<String>, b: List<String>): List<Op> {
        val n = a.size
        val m = b.size
        val cost = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) cost[i][0] = i
        for (j in 0..m) cost[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            var best = cost[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            best = minOf(best, cost[i - 1][j] + 1, cost[i][j - 1] + 1)
            if (i >= 2 && a[i - 2] + a[i - 1] == b[j - 1]) best = minOf(best, cost[i - 2][j - 1])
            if (j >= 2 && a[i - 1] == b[j - 2] + b[j - 1]) best = minOf(best, cost[i - 1][j - 2])
            cost[i][j] = best
        }
        val ops = ArrayList<Op>(n + m)
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            val here = cost[i][j]
            when {
                i > 0 && j > 0 && a[i - 1] == b[j - 1] && here == cost[i - 1][j - 1] -> {
                    ops += Op.MATCH; i--; j--
                }
                i >= 2 && j > 0 && a[i - 2] + a[i - 1] == b[j - 1] && here == cost[i - 2][j - 1] -> {
                    ops += Op.MERGE; i -= 2; j--
                }
                i > 0 && j >= 2 && a[i - 1] == b[j - 2] + b[j - 1] && here == cost[i - 1][j - 2] -> {
                    ops += Op.SPLIT; i--; j -= 2
                }
                i > 0 && j > 0 && here == cost[i - 1][j - 1] + 1 -> {
                    ops += Op.SUB; i--; j--
                }
                i > 0 && here == cost[i - 1][j] + 1 -> {
                    ops += Op.DEL; i--
                }
                else -> {
                    ops += Op.INS; j--
                }
            }
        }
        ops.reverse()
        return ops
    }

    private fun charDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                curr[j + 1] = minOf(prev[j + 1] + 1, curr[j] + 1, prev[j] + if (a[i] == b[j]) 0 else 1)
            }
            val swap = prev; prev = curr; curr = swap
        }
        return prev[b.length]
    }

    /**
     * Объединение разделителей вокруг выброшенного слова: «, » + «. » → «,. » недопустимо,
     * поэтому остаётся разделитель с более сильным знаком (конец предложения > запятая > прочее),
     * при равенстве — последний.
     */
    private fun mergeGaps(first: String, second: String): String {
        if (first.isEmpty()) return second
        if (second.isEmpty()) return first
        val firstMarks = first.trim()
        val secondMarks = second.trim()
        val marks = if (strength(firstMarks) > strength(secondMarks)) firstMarks else secondMarks
        val trailingSpace = second.endsWith(' ') || second.endsWith('\n') || (secondMarks.isEmpty() && second.isNotEmpty())
        return marks + if (trailingSpace) " " else ""
    }

    private fun strength(marks: String): Int = when {
        marks.any { it == '.' || it == '!' || it == '?' || it == '…' } -> 3
        marks.any { it == ',' || it == ';' || it == ':' } -> 2
        marks.isNotEmpty() -> 1
        else -> 0
    }

    /** Слово после конца предложения и первое слово текста — с заглавной буквы. */
    private fun capitalizeSentences(text: String): String {
        val chars = text.toCharArray()
        var capitalize = true
        for (index in chars.indices) {
            val c = chars[index]
            if (c.isLetter()) {
                if (capitalize) chars[index] = c.uppercaseChar()
                capitalize = false
            } else if (c.isDigit()) {
                capitalize = false
            } else if (c == '.' || c == '!' || c == '?' || c == '…') {
                capitalize = true
            }
        }
        return String(chars)
    }

    private companion object {
        /** Слово — непрерывная последовательность букв или цифр; дефис и апостроф разделяют слова. */
        val WORD: Regex = Regex("[\\p{L}\\p{N}]+")

        fun normalize(word: String): String = word.lowercase().replace('ё', 'е')
    }
}

/** Пороги пословного согласования; значения консервативные, подбор — на реальном наборе (#11). */
data class ReconcilerConfig(
    /** Слова короче этого не исправляются: «и»/«а», «в»/«с», «он»/«она» меняют смысл. */
    val minFixableLength: Int = 3,
    /** Слова, которые LLM не вправе ни заменять, ни подставлять вместо других. */
    val protectedWords: Set<String> = setOf(
        "не", "ни", "нет", "никогда", "ничего", "никто", "нигде", "никуда", "никак",
        "да", "без", "только", "уже", "еще", "все", "был", "была", "было", "были",
    ),
) {
    /** Допустимое число посимвольных правок для слова длиной [length]. */
    fun maxCharEditsFor(length: Int): Int = when {
        length <= 5 -> 1
        length <= 9 -> 2
        else -> 3
    }
}
