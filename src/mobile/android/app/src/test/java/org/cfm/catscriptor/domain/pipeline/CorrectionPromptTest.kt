package org.cfm.catscriptor.domain.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionPromptTest {

    @Test fun systemPromptDeclaresEditorRoleAndForbidsAdditions() {
        val system = CorrectionPrompt.SYSTEM.lowercase()
        assertTrue(system.contains("корректор"))
        assertTrue(system.contains("отрицания"))
        assertTrue(system.contains("числа"))
        assertTrue(system.contains("слово в слово"))
        assertTrue(system.contains("не удаляй, не добавляй и не переставляй"))
        assertTrue(system.contains("Только исправленный текст".lowercase()) ||
            system.lowercase().contains("верни только исправленный текст"))
    }

    @Test fun userMessageIsPlainTextByDefault() {
        assertEquals("привет как дела", CorrectionPrompt.buildUserMessage("привет как дела"))
    }

    @Test fun userMessageMayCarryPreviousUtteranceAsContext() {
        val message = CorrectionPrompt.buildUserMessage("да завтра", "давай встретимся")
        assertTrue(message.contains("давай встретимся"))
        assertTrue(message.contains("да завтра"))
    }

    @Test fun sanitizeStripsCodeFences() {
        assertEquals("Привет, как дела?", CorrectionPrompt.sanitize("```текст\nПривет, как дела?\n```"))
    }

    @Test fun sanitizeStripsSurroundingQuotes() {
        assertEquals("Привет.", CorrectionPrompt.sanitize("«Привет.»"))
        assertEquals("Привет.", CorrectionPrompt.sanitize("\"Привет.\""))
    }

    @Test fun sanitizeStripsLeadingLabel() {
        assertEquals("Привет, как дела?", CorrectionPrompt.sanitize("Исправленный текст: Привет, как дела?"))
        assertEquals("Привет.", CorrectionPrompt.sanitize("исправлено: Привет."))
    }

    @Test fun sanitizeNormalizesWhitespace() {
        assertEquals("Привет.\n\nКак дела?", CorrectionPrompt.sanitize("  Привет.\r\n\r\n\r\nКак дела?  "))
    }

    @Test fun sanitizeKeepsInteriorQuotes() {
        assertEquals("Он сказал «привет» и ушёл.", CorrectionPrompt.sanitize("Он сказал «привет» и ушёл."))
    }

    @Test fun sanitizeDoesNotTouchNormalText() {
        val text = "Ну, давай встретимся завтра, в пятницу."
        assertEquals(text, CorrectionPrompt.sanitize(text))
    }

    @Test fun sanitizeEmptyStaysEmpty() {
        assertEquals("", CorrectionPrompt.sanitize("   "))
    }

    @Test fun sanitizeStripsPrintedSpecialTokens() {
        assertEquals("Привет, как дела?", CorrectionPrompt.sanitize("Привет, как дела?<|im_end|>"))
        assertEquals("Привет.", CorrectionPrompt.sanitize("<|endoftext|>Привет.<|im_end|>"))
    }

    @Test fun systemPromptDoesNotAskForSpeakerOrTime() {
        // Спикеров и таймкоды модель не видит: их нельзя править и возвращать.
        assertFalse(CorrectionPrompt.SYSTEM.contains("speaker"))
    }
}
