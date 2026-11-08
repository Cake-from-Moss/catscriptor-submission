package org.cfm.catscriptor.app

import org.cfm.catscriptor.audio.background.AudioProcessingPhase
import org.cfm.catscriptor.audio.background.AudioProcessingState
import org.cfm.catscriptor.domain.app.RecordingNote
import org.cfm.catscriptor.domain.app.RecordingStatus
import org.cfm.catscriptor.domain.pipeline.Utterance
import org.junit.Assert.*
import org.junit.Test

class CorrectedTranscriptTest {
    private val source = listOf(Utterance("speaker_1", 100, 900, "привет"))
    private val result = listOf(source.single().copy(text = "Привет!"))
    private val entry = PipelineEntry("id", "Название", "content://source", "wav", 1, 1000,
        RecordingStatus.Ready, processingState = AudioProcessingState(AudioProcessingPhase.COMPLETED,
            speakerDraft = source, transcript = source),
        speakerDisplayNames = mapOf("speaker_1" to "Кот"), notes = listOf(RecordingNote("note", 500, "Важно")))

    @Test fun commitsOnlyTextAndPreservesMetadataAndRawDraft() {
        val renamed = entry.copy(title = "Новое название")
        val updated = renamed.withCorrectedText(source, result)
        assertEquals(result, updated.displayedTranscript())
        assertTrue(updated.textCorrected)
        assertTrue(updated.toSummary().textCorrected)
        assertEquals(source, updated.processingState!!.speakerDraft)
        assertEquals(renamed.notes, updated.notes)
        assertEquals(renamed.title, updated.title)
        assertEquals(renamed.speakerDisplayNames, updated.speakerDisplayNames)
    }

    @Test fun visibleManualEditsAreCorrectedRatherThanHiddenUnderOldOverlay() {
        val edited = entry.copy(editedTranscript = listOf(source.single().copy(text = "ручная правка")))
        val updated = edited.withCorrectedText(edited.correctionSource(), result)
        assertEquals(result, updated.editedTranscript)
        assertEquals(result, updated.displayedTranscript())
    }

    @Test fun alreadyCorrectedTextCanBeCorrectedAgain() {
        val once = entry.withCorrectedText(source, result)
        val again = listOf(result.single().copy(text = "Привет, коты!"))
        val twice = once.withCorrectedText(once.correctionSource(), again)
        assertTrue(twice.textCorrected)
        assertEquals(again, twice.displayedTranscript())
        assertEquals(source, twice.processingState!!.speakerDraft)
    }

    @Test fun rejectsConcurrentTextChangesHiddenRecordAndInvalidIntervals() {
        val invalid = listOf(result.single().copy(startMs = 101))
        for ((current, replacement) in listOf(
            entry.copy(editedTranscript = result) to result,
            entry.copy(hidden = true) to result,
            entry to invalid,
        )) {
            try { current.withCorrectedText(source, replacement); fail("Коммит должен быть отклонён") }
            catch (_: IllegalStateException) { }
        }
        assertEquals(source, entry.displayedTranscript())
    }
}
