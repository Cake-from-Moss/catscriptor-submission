package org.cfm.catscriptor.app

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cfm.catscriptor.MainActivity
import org.cfm.catscriptor.audio.background.AudioProcessingController
import org.cfm.catscriptor.audio.background.AudioProcessingPhase
import org.cfm.catscriptor.audio.background.AudioProcessingState
import org.cfm.catscriptor.domain.app.CorrectionLevel
import org.cfm.catscriptor.domain.app.RecordingNote
import org.cfm.catscriptor.domain.app.RecordingStatus
import org.cfm.catscriptor.domain.app.ProcessingStage
import org.cfm.catscriptor.domain.pipeline.Utterance
import org.junit.Assert.*
import org.junit.Test

/** Реальный сервис и bundled GGUF; недоступный аудио-URI доказывает отсутствие повторного ASR. */
class RepeatTextCorrectionTest {
    @Test fun oldTranscriptIsCorrectedWithoutAudioAndSurvivesReload() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        InstrumentationRegistry.getInstrumentation().startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertNotEquals(AudioProcessingPhase.RUNNING, AudioProcessingController.state.value.phase)
        val file = File.createTempFile("repeat-correction-test", ".json", context.cacheDir)
        val source = listOf(Utterance("speaker_1", 200, 1800, "привет как дела"))
        val original = PipelineEntry("fixture", "Старая запись", "content://unavailable/no-audio", "wav", 42, 2000,
            RecordingStatus.Ready, processingState = AudioProcessingState(AudioProcessingPhase.COMPLETED,
                transcript = source), notes = listOf(RecordingNote("note", 500, "Важно")),
            speakerDisplayNames = mapOf("speaker_1" to "Кот"))
        val disk = PipelineLibraryDisk(file)
        disk.save(emptyMap())
        val store = PipelineStore(disk)
        store.upsert(original)
        val controller = PipelineProcessingController(context, store)
        val started = SystemClock.elapsedRealtime()
        try {
            withTimeout(180_000) {
                val correcting = async { controller.correctText(original.id, CorrectionLevel.FAST) }
                while (AudioProcessingController.state.value.phase != AudioProcessingPhase.RUNNING && !correcting.isCompleted) delay(10)
                // Переименование во время LLM не должно затеряться при коммите результата.
                PipelineRecordingRepository(store).rename(original.id, "Новое название")
                assertEquals(source, store.entry(original.id)!!.displayedTranscript())
                correcting.await()
            }
            val restored = disk.load().getValue(original.id)
            val stored = checkNotNull(store.entry(original.id))
            assertTrue(stored.textCorrected)
            assertTrue(restored.textCorrected)
            assertEquals("Новое название", restored.title)
            assertEquals(original.notes, restored.notes)
            assertEquals(original.speakerDisplayNames, restored.speakerDisplayNames)
            assertEquals(RecordingStatus.Ready, restored.status)
            val corrected = restored.displayedTranscript().single()
            assertEquals(source.single(), corrected.copy(text = source.single().text))
            assertTrue(corrected.text.isNotBlank())
            assertEquals(ProcessingStage.CORRECT_TEXT, AudioProcessingController.state.value.stage)
            Log.i("RepeatCorrectionTest", "elapsed_ms=${SystemClock.elapsedRealtime() - started} changed=${corrected.text != source.single().text}")
        } finally {
            if (AudioProcessingController.state.value.phase == AudioProcessingPhase.RUNNING) {
                AudioProcessingController.cancel(context)
            }
            file.delete()
        }
        Unit
    }
}
