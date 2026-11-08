package org.cfm.catscriptor.app

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.cfm.catscriptor.audio.background.AudioProcessingPhase
import org.cfm.catscriptor.audio.background.AudioProcessingState
import org.cfm.catscriptor.domain.app.*
import org.cfm.catscriptor.domain.pipeline.AsrSegment
import org.cfm.catscriptor.domain.pipeline.TimeRange
import org.junit.Assert.*
import org.junit.Test

class PipelineRecordingRepositoryTest {
    @Test fun renameChangesOnlyTitleAndRejectsBlankInput() = runBlocking {
        val store = PipelineStore()
        val original = PipelineEntry("id", "Название", "content://audio", "wav", 1, 1000,
            RecordingStatus.Ready, notes = listOf(RecordingNote("note", 100, "Важно")))
        store.upsert(original)
        val repository = PipelineRecordingRepository(store)
        repository.rename("id", "  Диалог котов  ")
        assertEquals(original.copy(title = "Диалог котов"), store.entry("id"))
        try { repository.rename("id", "  "); fail("Пустое название недопустимо") }
        catch (_: IllegalArgumentException) { }
        assertEquals("Диалог котов", store.entry("id")!!.title)
    }

    @Test fun notesCanBeAddedEditedAndDeletedWithoutChangingTranscript() = runBlocking {
        val store = PipelineStore()
        val utterances = listOf(org.cfm.catscriptor.domain.pipeline.Utterance("speaker_1", 0, 900, "Речь"))
        store.upsert(PipelineEntry("id", "Разговор", "content://audio", "wav", 1, 1000, RecordingStatus.Ready,
            processingState = AudioProcessingState(AudioProcessingPhase.COMPLETED, transcript = utterances)))
        val repository = PipelineRecordingRepository(store)
        val before = repository.observeTranscript("id").first()!!
        repository.saveNote("id", null, 5000, "  Важная мысль  ")
        val note = repository.observeTranscript("id").first()!!.notes.single()
        assertEquals(1000L, note.positionMs)
        assertEquals("Важная мысль", note.text)
        repository.saveNote("id", note.id, 100, "Уточнение")
        repository.saveNote("id", null, -100, "")
        val edited = repository.observeTranscript("id").first()!!
        assertEquals(listOf(0L, 100L), edited.bookmarksMs)
        assertEquals(note.id, edited.notes.last().id)
        assertEquals("Уточнение", edited.notes.last().text)
        assertEquals(before.lines, edited.lines)
        assertEquals(TranscriptRendering.renderJsonPretty(before), TranscriptRendering.renderJsonPretty(edited))
        repository.saveNote("id", "missing", 0, "Не воскрешать удалённую пометку")
        assertEquals(2, repository.observeTranscript("id").first()!!.notes.size)
        repository.deleteNote("id", note.id)
        assertEquals("", repository.observeTranscript("id").first()!!.notes.single().text)
    }

    @Test fun diarizedDraftExposesRealSpeakersAndStaysNonExportable() = runBlocking {
        val store = PipelineStore()
        val utterances = listOf(
            org.cfm.catscriptor.domain.pipeline.Utterance("speaker_1", 1000, 2000, "Привет"),
            org.cfm.catscriptor.domain.pipeline.Utterance("speaker_2", 2500, 3500, "Здравствуй"),
        )
        store.upsert(PipelineEntry("id", "Диалог", "content://audio", "wav", 1, 4000, RecordingStatus.AsrReady,
            processingState = AudioProcessingState(AudioProcessingPhase.COMPLETED,
                asrDraft = utterances.map { AsrSegment(TimeRange(it.startMs, it.endMs), it.text) }, speakerDraft = utterances)))
        val repository = PipelineRecordingRepository(store)
        val document = requireNotNull(repository.observeTranscript("id").first())
        assertEquals(listOf("speaker_1", "speaker_2"), document.asrDraftSpeakers)
        assertNotNull(document.asrDraft)
        assertTrue(document.lines.isEmpty())
        val summary = repository.observe().first().single()
        assertEquals(2, summary.speakerCount)
    }

    @Test fun draftHasMetadataNoSpeakersAndCannotBeEdited() = runBlocking {
        val store = PipelineStore()
        val draft = listOf(AsrSegment(TimeRange(1500, 2500), "Кошачий разговор"))
        store.upsert(PipelineEntry("id", "Название", "content://audio", "wav", 123, 3000,
            RecordingStatus.AsrReady, processingState = AudioProcessingState(
                AudioProcessingPhase.COMPLETED, asrDraft = draft)))
        val repository = PipelineRecordingRepository(store)
        val document = requireNotNull(repository.observeTranscript("id").first())
        assertEquals("Название", document.title)
        assertEquals(3000L, document.durationMs)
        assertEquals(draft, document.asrDraft)
        assertTrue(document.lines.isEmpty())
        assertTrue(document.speakers.isEmpty())
        assertEquals(1, repository.observe(RecordingQuery("кошачий", RecordingFilter.READY)).first().size)
        assertTrue(repository.observe(RecordingQuery(filter = RecordingFilter.PROCESSING)).first().isEmpty())
        repository.editLineText("id", "fake", "Подмена")
        repository.renameSpeaker("id", "speaker_1", "Подмена")
        repository.changeLineSpeaker("id", "fake", "speaker_1")
        assertEquals(document, repository.observeTranscript("id").first())
        repository.rename("id", "Другое название")
        assertEquals("Другое название", repository.observeTranscript("id").first()!!.title)
        repository.delete("id")
        assertNull(repository.observeTranscript("id").first())
        repository.restore("id")
        assertEquals(draft, repository.observeTranscript("id").first()!!.asrDraft)
    }

    @Test fun finalTranscriptExposesEditableLinesAndSpeakerNames() = runBlocking {
        val store = PipelineStore()
        val utterances = listOf(
            org.cfm.catscriptor.domain.pipeline.Utterance("speaker_1", 1000, 2000, "привет"),
            org.cfm.catscriptor.domain.pipeline.Utterance("speaker_2", 2500, 3500, "здравствуй"),
        )
        store.upsert(PipelineEntry("id", "Диалог", "content://audio", "wav", 1, 4000, RecordingStatus.Ready,
            processingState = AudioProcessingState(AudioProcessingPhase.COMPLETED, transcript = utterances)))
        val repository = PipelineRecordingRepository(store)

        val document = requireNotNull(repository.observeTranscript("id").first())
        assertNull(document.asrDraft)
        assertEquals(listOf("speaker_1", "speaker_2"), document.speakers.map { it.id })
        assertEquals(listOf("привет", "здравствуй"), document.lines.map { it.text })
        assertTrue(document.speakers.all { it.displayName == null })
        assertEquals(0.5f, document.speakers.single { it.id == "speaker_1" }.shareFraction, 0.001f)

        repository.editLineText("id", document.lines.first().id, "Привет!")
        repository.renameSpeaker("id", "speaker_2", "Мария")
        val edited = requireNotNull(repository.observeTranscript("id").first())
        assertEquals("Привет!", edited.lines.first().text)
        assertEquals("Мария", edited.speakers.single { it.id == "speaker_2" }.displayName)
        // Таймкоды и спикеры от правки текста не меняются.
        assertEquals(document.lines.first().startMs, edited.lines.first().startMs)
        assertEquals(document.lines.first().speakerId, edited.lines.first().speakerId)

        repository.changeLineSpeaker("id", edited.lines.first().id, "speaker_2")
        val moved = requireNotNull(repository.observeTranscript("id").first())
        assertEquals("speaker_2", moved.lines.first().speakerId)
        assertEquals(1f, moved.speakers.single { it.id == "speaker_2" }.shareFraction, 0.001f)

        val summary = repository.observe().first().single()
        // Обе реплики после смены спикера принадлежат speaker_2.
        assertEquals(1, summary.speakerCount)
        assertEquals("speaker_2", summary.previewSpeakerId)
        assertEquals("Привет!", summary.previewText)
    }

    @Test fun silenceDraftIsNotConfusedWithMissingTranscript() = runBlocking {
        val store = PipelineStore()
        val repository = PipelineRecordingRepository(store)
        store.upsert(PipelineEntry("id", "Тишина", "content://audio", "wav", 0, 1000,
            RecordingStatus.Queued))
        assertNull(repository.observeTranscript("id").first())
        store.update("id") { it.copy(status = RecordingStatus.AsrReady,
            processingState = AudioProcessingState(AudioProcessingPhase.COMPLETED, asrDraft = emptyList())) }
        assertEquals(emptyList<AsrSegment>(), repository.observeTranscript("id").first()!!.asrDraft)
    }
}
