package org.cfm.catscriptor.domain.pipeline

/**
 * Промпт и очистка ответа для LLM-коррекции ASR (issue #7).
 * Системная инструкция держит модель в роли редактора стенограммы: только исправление
 * предоставленного текста, без додумывания. [sanitize] не доверяет формату вывода:
 * снимает code fences, обрамляющие кавычки и служебные подписи до проверки [CorrectionGuard].
 */
object CorrectionPrompt {

    /**
     * Системная инструкция на русском; правила согласованы с ограничениями issue #7.
     * Формулировка «слово в слово» и пример подобраны прогоном Qwen2.5-1.5B Q4_K_M (жадно):
     * прежняя редакция («исправь очевидные ошибки, не пересказывай») сохраняла все слова
     * в 4 из 10 тестовых реплик, эта — в 6–7 из 10. Оставшиеся пропуски и подмены
     * исправляет [CorrectionReconciler].
     */
    const val SYSTEM: String = """
Ты — корректор автоматической расшифровки русской речи: расставляешь знаки препинания и заглавные буквы.

Правила:
1. Перепиши текст слово в слово: каждое слово исходного текста должно остаться на своём месте. Не удаляй, не добавляй и не переставляй слова, даже повторы и слова-паразиты.
2. Добавь запятые, точки, вопросительные знаки и заглавные буквы.
3. Слово можно исправить, только если в нём явная орфографическая ошибка распознавания (одна-две буквы), например «малако» → «молоко». Отрицания, числа и имена не меняй.
4. Верни только исправленный текст, без пояснений.

Пример.
Текст: ну я не знаю не знаю может завтра пайдём к ветеринару
Ответ: Ну, я не знаю, не знаю, может, завтра пойдём к ветеринару.
"""

    /**
     * Собрать user-сообщение. По умолчанию — только текст реплики; [previousUtterance] опционально
     * даёт контекст предыдущей реплики (соседние реплики не объединяются и не переставляются).
     */
    fun buildUserMessage(text: String, previousUtterance: String? = null): String {
        val previous = previousUtterance?.trim().orEmpty()
        if (previous.isEmpty()) return text
        return "Предыдущая реплика:\n$previous\n\nРеплика для исправления:\n$text"
    }

    /**
     * Очистить сырой вывод модели: убрать code fences, обрамляющие кавычки, служебные подписи,
     * нормализовать пробелы и вырезать «напечатанные буквами» служебные токены вида <|im_end|>.
     * Результат всё равно проходит через [CorrectionGuard].
     */
    fun sanitize(raw: String): String {
        var text = raw.trim()

        // Code fences: ``` или ```text в начале и закрывающий ``` в конце.
        if (text.startsWith("```")) {
            val firstLineEnd = text.indexOf('\n')
            if (firstLineEnd >= 0) {
                text = text.substring(firstLineEnd + 1)
                if (text.endsWith("```")) {
                    text = text.substring(0, text.length - 3)
                }
                text = text.trim()
            }
        }

        // Одна служебная подпись в начале: «Исправленный текст: …».
        val label = LABEL_AT_START.matchAt(text, 0)
        if (label != null) {
            text = text.substring(label.range.last + 1).trimStart()
        }

        // Маркеры спецтокенов, которые модель иногда «печатает» буквами (<|im_end|>, <|endoftext|>).
        text = text.replace(SPECIAL_TOKEN_TEXT, "")

        // Обрамляющие кавычки, парные или одинаковые.
        text = stripWrappingQuotes(text)

        // Нормализация пробелов: CRLF -> LF, табы/двойные пробелы, пустые строки.
        text = text.replace("\r\n", "\n")
        text = text.replace(Regex("[ \\t]{2,}"), " ")
        text = text.replace(Regex("\\n{3,}"), "\n\n")
        return text.trim()
    }

    private fun stripWrappingQuotes(text: String): String {
        if (text.length < 2) return text
        val pairs = listOf('«' to '»', '"' to '"', '“' to '”', '\'' to '\'')
        for ((open, close) in pairs) {
            if (text.first() == open && text.last() == close) {
                return text.substring(1, text.length - 1).trim()
            }
        }
        return text
    }

    private val LABEL_AT_START: Regex = Regex(
        "^(исправленный текст|исправленный вариант|исправлено|вариант|ответ|текст|result|corrected text)\\s*:",
        RegexOption.IGNORE_CASE,
    )

    /** Служебные токены, «напечатанные буквами» (<|im_end|> и подобные). */
    private val SPECIAL_TOKEN_TEXT: Regex = Regex("<\\|[a-zA-Z_0-9\\-]+\\|>")
}
