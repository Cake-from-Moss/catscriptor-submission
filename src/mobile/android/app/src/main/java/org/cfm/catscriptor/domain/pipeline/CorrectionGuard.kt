package org.cfm.catscriptor.domain.pipeline

/**
 * Консервативная проверка допустимости правки LLM.
 *
 * Сравнивает исходный ASR-текст и кандидата от LLM не по сырой строке (пунктуация и регистр
 * меняют символы, но не слова), а по нормализованным последовательностям слов, а также
 * проверяет защищённые сущности: цифры, отрицания, общий объём текста.
 *
 * Фильтр намеренно консервативен: лучше отклонить полезную правку, чем пропустить
 * галлюцинацию. Он не доказывает сохранение смысла — только отсекает типовые поломки.
 * Все пороги задаются [CorrectionGuardConfig].
 *
 * Прошедший проверки кандидат не применяется как есть: [CorrectionReconciler] собирает итоговый
 * текст из слов ASR и пунктуации/регистра LLM, поэтому удалённые слова возвращаются,
 * добавленные выбрасываются, а замена слова допускается только как близкая по написанию.
 */
class CorrectionGuard(
    private val config: CorrectionGuardConfig = CorrectionGuardConfig(),
    private val reconciler: CorrectionReconciler = CorrectionReconciler(),
) {

    /** Результат проверки: [Decision.accept] — можно применять, иначе вернуть исходный текст. */
    sealed interface Decision {
        val accept: Boolean
        val reason: String

        /** Правка допустима; [text] — итоговый текст после пословного согласования с ASR. */
        data class Accept(val text: String, val details: CorrectionReconciler.Result) : Decision {
            override val accept: Boolean get() = true
            override val reason: String get() = "OK"
        }

        /** Применять правку нельзя; вернуть исходный ASR-текст. */
        data class UseOriginal(override val reason: String) : Decision {
            override val accept: Boolean get() = false
        }
    }

    fun verify(original: String, candidate: String): Decision {
        if (original.isBlank()) return Decision.UseOriginal("EMPTY_ORIGINAL")
        val cleaned = candidate.trim()
        if (cleaned.isEmpty()) return Decision.UseOriginal("EMPTY_CANDIDATE")

        val sourceWords = normalizeWords(original)
        val candidateWords = normalizeWords(cleaned)
        if (candidateWords.isEmpty()) return Decision.UseOriginal("EMPTY_CANDIDATE")

        // Meta-commentary и служебный вывод LLM.
        val lowered = cleaned.lowercase()
        for (marker in config.metaMarkers) {
            if (lowered.contains(marker)) return Decision.UseOriginal("META_COMMENTARY")
        }

        // Цифры: токены с цифрами должны сохраниться как мультимножество (15:30 и "15 30" дают "15","30").
        if (digitRuns(original).sorted() != digitRuns(cleaned).sorted()) {
            return Decision.UseOriginal("DIGITS_CHANGED")
        }

        // Отрицания: их число не должно ни уменьшаться, ни увеличиваться — иначе смысл перевёрнут.
        for (negation in config.negationWords) {
            if (countWord(candidateWords, negation) != countWord(sourceWords, negation)) {
                return Decision.UseOriginal("NEGATION_CHANGED")
            }
        }

        // Ограничение роста по символам: пунктуация добавляет немного, текст LLM — много.
        val maxChars = (original.length * config.maxLengthGrowthRatio + config.lengthGrowthSlackChars).toInt()
        if (cleaned.length > maxChars) return Decision.UseOriginal("TOO_LONG")

        // Число предложений не должно заметно расти (добавленные фразы, продолжение диалога).
        val sourceSentences = sentenceCount(original)
        val candidateSentences = sentenceCount(cleaned)
        if (candidateSentences > sourceSentences + config.maxAddedSentences) {
            return Decision.UseOriginal("SENTENCES_ADDED")
        }

        // Добавленные слова: абсолютный люфт + доля от длины исходника.
        val maxWords = sourceWords.size + config.maxAddedWords +
            (sourceWords.size * config.maxAddedWordRatio).toInt()
        if (candidateWords.size > maxWords) return Decision.UseOriginal("WORDS_ADDED")

        // Сохранность слов исходника по LCS: пунктуация/регистр не влияют, перестановки видны.
        val kept = lcsLength(sourceWords, candidateWords)
        if (kept < sourceWords.size * config.minWordRetentionRatio) {
            return Decision.UseOriginal("WORD_LOSS")
        }

        // Общая близость словых последовательностей (замены слов ограничены).
        val edits = wordEditDistance(sourceWords, candidateWords, config.maxWordEditDistanceCap)
        if (edits.toDouble() / sourceWords.size > config.maxWordEditRatio) {
            return Decision.UseOriginal("TOO_DIFFERENT")
        }

        val reconciled = reconciler.reconcile(original, cleaned)
        if (reconciled.text.isBlank()) return Decision.UseOriginal("EMPTY_CANDIDATE")
        return Decision.Accept(reconciled.text, reconciled)
    }

    /** Нормализация: ё→е, нижний регистр, только буквы и цифры как слова. */
    private fun normalizeWords(text: String): List<String> {
        val lowered = text.lowercase().replace('ё', 'е')
        return WORD_TOKEN.findAll(lowered).map { it.value }.toList()
    }

    private fun digitRuns(text: String): List<String> = DIGIT_RUN.findAll(text).map { it.value }.toList()

    private fun countWord(words: List<String>, word: String): Int = words.count { it == word }

    private fun sentenceCount(text: String): Int {
        val sentences = SENTENCE_SPLIT.split(text.trim()).count { it.isNotBlank() }
        return sentences.coerceAtLeast(1)
    }

    /** Длина наибольшей общей подпоследовательности словых токенов. */
    private fun lcsLength(a: List<String>, b: List<String>): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        var prev = IntArray(b.size + 1)
        var curr = IntArray(b.size + 1)
        for (i in a.indices) {
            for (j in b.indices) {
                curr[j + 1] = if (a[i] == b[j]) prev[j] + 1 else maxOf(prev[j + 1], curr[j])
            }
            val swap = prev; prev = curr; curr = swap
            curr.fill(0)
        }
        return prev[b.size]
    }

    /**
     * Редакционное расстояние между словыми последовательностями с потолком [cap]:
     * как только расстояние достигает cap, досчитывать бессмысленно — проверка всё равно отклонит.
     */
    private fun wordEditDistance(a: List<String>, b: List<String>, cap: Int): Int {
        var prev = IntArray(b.size + 1) { it }
        var curr = IntArray(b.size + 1)
        for (i in a.indices) {
            curr[0] = i + 1
            var rowMin = curr[0]
            for (j in b.indices) {
                curr[j + 1] = minOf(
                    prev[j + 1] + 1,
                    curr[j] + 1,
                    prev[j] + if (a[i] == b[j]) 0 else 1,
                )
                rowMin = minOf(rowMin, curr[j + 1])
            }
            if (rowMin >= cap) return cap
            val swap = prev; prev = curr; curr = swap
        }
        return prev[b.size].coerceAtMost(cap)
    }

    private companion object {
        val WORD_TOKEN: Regex = Regex("[0-9]+|[a-zа-я]+")
        val DIGIT_RUN: Regex = Regex("[0-9]+")
        val SENTENCE_SPLIT: Regex = Regex("[.!?…]+")
    }
}

/** Пороги защиты от галлюцинаций; значения по умолчанию намеренно консервативны. */
data class CorrectionGuardConfig(
    /** Максимум новых предложений сверх исходного числа. */
    val maxAddedSentences: Int = 2,
    /** Абсолютный люфт добавленных слов. */
    val maxAddedWords: Int = 3,
    /** Доля добавленных слов от длины исходника (сверх абсолютного люфта). */
    val maxAddedWordRatio: Double = 0.15,
    /** Доля сохранённых слов исходника (LCS), ниже — отклонить. */
    val minWordRetentionRatio: Double = 0.6,
    /** Доля словых правок (редакционное расстояние / слов исходника), выше — отклонить. */
    val maxWordEditRatio: Double = 0.35,
    /** Потолок для подсчёта редакционного расстояния (ранний выход). */
    val maxWordEditDistanceCap: Int = 64,
    /** Рост строки по символам относительно исходника. */
    val maxLengthGrowthRatio: Double = 1.4,
    /** Абсолютный люфт роста строки в символах (на пунктуацию и кавычки). */
    val lengthGrowthSlackChars: Int = 24,
    /** Слова-отрицания, которые нельзя терять и нельзя добавлять. */
    val negationWords: Set<String> = setOf(
        "не", "нет", "ни", "никогда", "никак", "ничего", "ничто", "никто", "нигде",
        "никуда", "откуда", "никто", "без", "вряд", "едва",
    ),
    /** Маркеры meta-commentary в ответе модели. */
    val metaMarkers: Set<String> = setOf(
        "исправленный текст", "исправлено:", "вот исправлен", "вот вариант", "вариант исправления",
        "как искусственный интеллект", "как языковая модель", "могу помочь", "могу еще",
        "надеюсь,", "если нужно", "если хотите", "позвольте", "к сожалению,", "вот результат",
    ),
)
