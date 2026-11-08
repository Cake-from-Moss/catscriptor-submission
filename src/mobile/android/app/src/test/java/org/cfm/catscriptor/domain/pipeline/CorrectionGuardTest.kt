package org.cfm.catscriptor.domain.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Поведение защиты от галлюцинаций: что принимаем, что отклоняем и с какой причиной. */
class CorrectionGuardTest {
    private val guard = CorrectionGuard()

    private fun accept(original: String, candidate: String) {
        val decision = guard.verify(original, candidate)
        assertTrue("ожидался Accept для '$candidate' (от '$original'), получено $decision", decision.accept)
    }

    private fun reject(original: String, candidate: String, reason: String) {
        val decision = guard.verify(original, candidate)
        assertFalse("ожидался отказ для '$candidate'", decision.accept)
        assertEquals(reason, (decision as CorrectionGuard.Decision.UseOriginal).reason)
    }

    @Test fun acceptsCasingPunctuationAndQuestionMark() =
        accept("привет как дела", "Привет, как дела?")

    @Test fun acceptsCommasInRunOnSentence() =
        accept("ну давай встретимся завтра в пятницу", "Ну, давай встретимся завтра, в пятницу.")

    @Test fun acceptsObviousAsrWordFix() =
        accept("я еду в москву на вакзал", "Я еду в Москву на вокзал.")

    @Test fun preservesNegationAccept() =
        accept("я не приду завтра", "Я не приду завтра.")

    @Test fun rejectsRemovedNegation() =
        reject("я не приду завтра", "Я приду завтра.", "NEGATION_CHANGED")

    @Test fun rejectsAddedNegationFlippingMeaning() =
        reject("я приду завтра", "Я не приду завтра.", "NEGATION_CHANGED")

    @Test fun acceptsTimeDigitsWithPunctuationChange() =
        accept("встреча в 15 30", "Встреча в 15:30.")

    @Test fun rejectsChangedDigits() =
        reject("встреча в 15 30", "Встреча в 17:30.", "DIGITS_CHANGED")

    @Test fun acceptsDigitsInDates() =
        accept("договорились на 3 мая", "Договорились на 3 мая.")

    @Test fun rejectsChangedDateNumber() =
        reject("договорились на 3 мая", "Договорились на 8 мая.", "DIGITS_CHANGED")

    @Test fun keepsProperNamesAccept() =
        accept("лена сказала что придет", "Лена сказала, что придет.")

    @Test fun preservesColloquialismsAccept() =
        accept("ну короче типа мы пошли", "Ну, короче, типа мы пошли.")

    @Test fun rejectsAddedFollowUpSentence() =
        reject("я не приду завтра", "Я не приду завтра. А ты придешь?", "TOO_DIFFERENT")

    @Test fun rejectsRetellingWithDifferentWording() =
        reject(
            "ну давай встретимся завтра в пятницу",
            "Хорошо, предлагаю встретиться завтра вечером в пятницу.",
            "WORD_LOSS",
        )

    @Test fun rejectsDroppedHalfOfText() =
        reject(
            "мы договорились встретиться завтра в пятницу в три часа дня у входа",
            "Мы договорились встретиться.",
            "WORD_LOSS",
        )

    @Test fun emptyOriginalKeepsOriginal() =
        reject("   ", "Привет.", "EMPTY_ORIGINAL")

    @Test fun emptyCandidateRejected() =
        reject("привет", "   ", "EMPTY_CANDIDATE")

    @Test fun punctuationOnlyCleanupOfGarbageCandidate() =
        reject("привет", "\n\n  ", "EMPTY_CANDIDATE")

    @Test fun rejectsMarkdownWrappedEcho() =
        reject(
            "привет",
            "```текст\nпривет\n```",
            "TOO_DIFFERENT",
        )

    @Test fun rejectsPromptEcho() =
        reject(
            "привет как дела",
            "Исправленный текст: Привет, как дела? Вот исправленный вариант, надеюсь, помог.",
            "META_COMMENTARY",
        )

    @Test fun rejectsAssistantOfferToHelp() =
        reject("завтра встреча", "Завтра встреча. Если нужно, могу еще помочь.", "META_COMMENTARY")

    @Test fun rejectsMultipleVariants() =
        reject(
            "привет",
            "1. Привет. 2. Привет! Выбирайте любой вариант.",
            "DIGITS_CHANGED",
        )

    @Test fun rejectsVeryLongContinuation() =
        reject(
            "да",
            "Да. Кстати, насчет встречи: давайте перенесем на пятницу, а потом обсудим детали и все остальное.",
            "TOO_LONG",
        )

    @Test fun handlesVeryLongInputQuicklyAndKeepsPunctuationFix() {
        val longOriginal = (1..300).joinToString(" ") { "слово$it" }
        accept(longOriginal, longOriginal.replaceFirst("слово1", "Слово1") + ".")
    }

    @Test fun handlesReplacementCharsAndEmojiWithoutCrash() =
        accept("привет \uFFFD как дела 👍", "Привет, как дела?")

    @Test fun mixedCyrillicLatinAccepted() =
        accept("зайди на сайт rambler", "Зайди на сайт Rambler.")

    @Test fun rejectsMeaningRewriteKeepingSomeWords() =
        reject(
            "кошка сидит на столе",
            "Кошка гуляет по крыше.",
            "WORD_LOSS",
        )

    @Test fun emojiOnlyCandidateRejected() =
        reject("привет", "👍👍", "EMPTY_CANDIDATE")

    @Test fun nearIdenticalLongUtteranceAccepted() {
        val original = "мы обсудили план работы на следующую неделю и договорились о встрече"
        accept(original, "Мы обсудили план работы на следующую неделю и договорились о встрече.")
    }

    @Test fun configurableThresholdsAreUsed() {
        // Пунктуация не меняет слова: жёсткий порог сохранности слов всё равно пропускает.
        val strict = CorrectionGuard(CorrectionGuardConfig(minWordRetentionRatio = 0.99))
        assertTrue(strict.verify("привет как дела", "Привет, как дела?").accept)
        // Разрешительный порог правок пропускает добавленное предложение.
        val loose = CorrectionGuard(CorrectionGuardConfig(maxWordEditRatio = 0.9))
        assertTrue(loose.verify("я не приду завтра", "Я не приду завтра. А ты придешь?").accept)
        // Тот же кейс при пороге по умолчанию отклоняется.
        assertFalse(guard.verify("я не приду завтра", "Я не приду завтра. А ты придешь?").accept)
    }
}
