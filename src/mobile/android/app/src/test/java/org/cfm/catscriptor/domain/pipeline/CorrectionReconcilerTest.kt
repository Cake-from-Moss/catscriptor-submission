package org.cfm.catscriptor.domain.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пословное согласование ответа LLM с ASR. Кандидаты в первых тестах — настоящие ответы
 * Qwen2.5-1.5B-Instruct Q4_K_M (жадное декодирование) на транскрипт без пунктуации.
 */
class CorrectionReconcilerTest {
    private val reconciler = CorrectionReconciler()

    private fun words(text: String) = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase().replace('ё', 'е')).map { it.value }.toList()

    @Test fun restoresWordsDroppedByModel() {
        val result = reconciler.reconcile(
            "привет ты сегодня кормил кота или опять забыл",
            "Привет, я сегодня кормил кота.",
        )
        // «ты» → «я» — подмена короткого слова, «или опять забыл» модель выбросила.
        assertEquals("Привет, ты сегодня кормил кота или опять забыл.", result.text)
        assertEquals(3, result.restoredWords)
        assertEquals(1, result.rejectedSubstitutions)
    }

    @Test fun dropsInventedWordAndRestoresReplacedPronoun() {
        val result = reconciler.reconcile(
            "а ещё купить новую когтеточку и он теперь дерёт диван",
            "А ещё надо купить новую когтеточку. И теперь Барсик дерёт диван.",
        )
        assertEquals("А ещё купить новую когтеточку. И он теперь дерёт диван.", result.text)
    }

    @Test fun keepsRepeatsTheModelCollapsed() {
        val original = "да да да конечно я понял я понял"
        val result = reconciler.reconcile(original, "Да, да, да. Конечно, я понял.")
        assertEquals(words(original), words(result.text))
        assertTrue(result.text, result.text.startsWith("Да, да, да. Конечно"))
    }

    @Test fun takesPunctuationCasingAndHyphenFromModel() {
        val result = reconciler.reconcile(
            "хорошо только найди его она где то под диваном",
            "Хорошо, только найди его. Она где-то под диваном.",
        )
        assertEquals("Хорошо, только найди его. Она где-то под диваном.", result.text)
    }

    @Test fun acceptsSpellingFixOfRecognitionError() {
        val result = reconciler.reconcile("я еду на вакзал", "Я еду на вокзал.")
        assertEquals("Я еду на вокзал.", result.text)
        assertEquals(1, result.acceptedFixes)
    }

    @Test fun rejectsReplacementByDifferentWord() {
        // «сидит» → «лежит» — три буквы из пяти: это другое слово, а не ошибка распознавания.
        val result = reconciler.reconcile("кот сидит на окне", "Кот лежит на окне.")
        assertEquals("Кот сидит на окне.", result.text)
        assertEquals(1, result.rejectedSubstitutions)
    }

    @Test fun shortAndProtectedWordsAreNeverReplaced() {
        assertFalse(reconciler.isSpellingFix("и", "а"))
        assertFalse(reconciler.isSpellingFix("не", "на"))
        assertFalse(reconciler.isSpellingFix("уже", "еще"))
        assertFalse(reconciler.isSpellingFix("15", "16"))
        assertTrue(reconciler.isSpellingFix("малако", "молоко"))
    }

    @Test fun mergeWithoutChangingLettersIsAccepted() {
        val result = reconciler.reconcile("я по этому и пришел", "Я поэтому и пришел.")
        assertEquals("Я поэтому и пришел.", result.text)
    }

    @Test fun identicalTextOnlyGetsCapitalized() {
        assertEquals("Кот спит", reconciler.reconcile("кот спит", "кот спит").text)
    }

    @Test fun capitalizesSentenceStartAfterRestoredWords() {
        val result = reconciler.reconcile("ну ладно пока", ". Пока.")
        // Модель потеряла начало; восстановленные слова начинают текст с заглавной буквы.
        assertEquals(words("ну ладно пока"), words(result.text))
        assertTrue(result.text.first().isUpperCase())
    }

    @Test fun wordSequenceAlwaysMatchesSourceExceptSpellingFixes() {
        val original = "мы с барсиком утром ходили к ветеринару он сказал что всё хорошо"
        val candidate = "Мы с Барсиком утром ходили к ветеринару. Ветеринар сказал, что всё очень хорошо!"
        val result = reconciler.reconcile(original, candidate)
        assertEquals(words(original), words(result.text))
        assertTrue(result.text, result.text.endsWith("хорошо!"))
    }

    @Test fun droppedInsertionKeepsStrongerMark() {
        assertEquals("Привет. Как дела?", reconciler.reconcile("привет как дела", "Привет. Ну, как дела?").text)
    }

    @Test fun restoredWordKeepsPunctuationAfterPrecedingWord() {
        assertEquals("Кот, сидит дома.", reconciler.reconcile("кот сидит дома", "Кот, дома.").text)
    }
}
