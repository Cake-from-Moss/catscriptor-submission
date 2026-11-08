package org.cfm.catscriptor.app

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.io.File
import org.cfm.catscriptor.audio.background.AudioProcessingPhase
import org.cfm.catscriptor.audio.background.AudioProcessingState
import org.cfm.catscriptor.domain.app.RecordingStatus
import org.cfm.catscriptor.domain.pipeline.Utterance
import org.junit.Assert.*
import org.junit.Test

class PipelineLibraryDiskTest {
    @Test fun deletionRemovesOnlyOwnedAudioAfterUndoWindow() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "delete-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val owned = File(root, "owned.wav").apply { writeText("owned") }
        val external = File.createTempFile("external-test", ".wav", context.cacheDir).apply { writeText("keep") }
        val store = PipelineStore()
        val repository = PipelineRecordingRepository(store, root)
        fun entry(id: String, path: String?) = PipelineEntry(id, id, "content://external/$id", "wav",
            1, 100, RecordingStatus.Ready, ownedAudioPath = path)
        try {
            store.upsert(entry("owned", owned.path))
            store.upsert(entry("external", null))
            repository.delete("owned")
            repository.commitDelete("owned")
            assertTrue(owned.exists())
            repository.restore("owned")
            kotlinx.coroutines.delay(5200)
            assertTrue(owned.exists())
            assertFalse(store.entry("owned")!!.hidden)
            repository.delete("owned")
            repository.delete("external")
            kotlinx.coroutines.withTimeout(15000) {
                while (store.entry("owned") != null || store.entry("external") != null) kotlinx.coroutines.delay(50)
            }
            assertFalse(owned.exists())
            assertEquals("keep", external.readText())
        } finally { owned.delete(); external.delete(); root.delete() }
    }

    @Test fun restartPreservesTranscriptEditsAndTombstone() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("library-test", ".json", context.cacheDir)
        file.delete()
        try {
            val text = listOf(Utterance("speaker_1", 10, 200, "Исходный текст"))
            val edits = text.map { it.copy(text = "Правка") }
            val original = PipelineEntry("test", "Разговор", "content://external/audio", "wav", 42, 200,
                RecordingStatus.Ready, hidden = true, pendingCommitDelete = true,
                processingState = AudioProcessingState(phase = AudioProcessingPhase.COMPLETED, transcript = text),
                editedTranscript = edits, speakerDisplayNames = mapOf("speaker_1" to "Анна"),
                deletedAtEpochMs = 123, textCorrected = true,
                notes = listOf(org.cfm.catscriptor.domain.app.RecordingNote("note", 42, "Пометка")))
            PipelineLibraryDisk(file).save(linkedMapOf(original.id to original))
            val restored = PipelineLibraryDisk(file).load().getValue("test")
            assertEquals(text, restored.processingState?.transcript)
            assertEquals(edits, restored.displayedTranscript())
            assertEquals(original.speakerDisplayNames, restored.speakerDisplayNames)
            assertEquals(original.notes, restored.notes)
            assertTrue(restored.hidden)
            assertTrue(restored.textCorrected)
            assertTrue(restored.pendingCommitDelete)
            assertEquals(123L, restored.deletedAtEpochMs)
            assertNull(restored.ownedAudioPath)
        } finally { file.delete() }
    }

    @Test fun legacySnapshotWithoutNotesLoadsAndCanBeUpdated() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("library-legacy-test", ".json", context.cacheDir)
        try {
            file.writeText("""{"version":1,"entries":[{"id":"legacy","title":"Старая запись","uri":"content://external/audio","extension":"wav","created":42,"duration":200,"status":"ready","stage":"PREPARE_AUDIO","hidden":false,"speakers":{}}]}""")
            val disk = PipelineLibraryDisk(file)
            val restored = disk.load().getValue("legacy")
            assertTrue(restored.notes.isEmpty())
            assertFalse(restored.textCorrected)
            val updated = restored.copy(notes = listOf(org.cfm.catscriptor.domain.app.RecordingNote("n", 100, "Новая")))
            disk.save(linkedMapOf(updated.id to updated))
            assertEquals(updated.notes, disk.load().getValue("legacy").notes)
        } finally { file.delete() }
    }
}
