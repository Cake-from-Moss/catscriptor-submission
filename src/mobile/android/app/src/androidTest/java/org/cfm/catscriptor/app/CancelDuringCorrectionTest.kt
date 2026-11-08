package org.cfm.catscriptor.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cfm.catscriptor.MainActivity
import org.cfm.catscriptor.audio.background.AudioProcessingController
import org.cfm.catscriptor.audio.background.AudioProcessingPhase
import org.cfm.catscriptor.domain.app.CorrectionLevel
import org.cfm.catscriptor.domain.app.ImportResult
import org.cfm.catscriptor.domain.app.ProcessingStage
import org.cfm.catscriptor.domain.app.RecordingStatus
import org.cfm.catscriptor.domain.app.StageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Настоящий конвейер и LLM: отмена на этапе правки текста (после подтверждения в UI) не оставляет запись
 * в ошибке, а сохраняет её без этого этапа: распознанный текст со спикерами и таймкодами, этап «пропущен».
 */
class CancelDuringCorrectionTest {
    @Test fun cancelWhileLlmRunsKeepsRecordingWithoutCorrection() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        InstrumentationRegistry.getInstrumentation().startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val directory = File(context.filesDir, "recordings").apply { mkdirs() }
        val audio = File(directory, "cancel-during-correction.wav")
        InstrumentationRegistry.getInstrumentation().context.assets.open("sherpa-example.wav").use { input ->
            audio.outputStream().use { input.copyTo(it) }
        }
        val disk = PipelineLibraryDisk(File.createTempFile("cancel-correction-test", ".json", context.cacheDir))
        disk.save(emptyMap())
        val store = PipelineStore(disk)
        val controller = PipelineProcessingController(
            context, store, speakerCount = { null }, correctionLevel = { CorrectionLevel.FAST },
        )
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.recordings", audio).toString()
            val id = (controller.importFile(uri, audio.name) as ImportResult.Accepted).recordingId

            withTimeout(300_000) {
                while (!(AudioProcessingController.state.value.phase == AudioProcessingPhase.RUNNING &&
                        AudioProcessingController.state.value.stage == ProcessingStage.CORRECT_TEXT)) delay(20)
            }
            controller.cancel(id)

            withTimeout(60_000) { while (store.entry(id)?.status != RecordingStatus.Ready) delay(50) }
            val entry = checkNotNull(store.entry(id))
            assertFalse("правка текста не выполнялась", entry.textCorrected)
            assertTrue(entry.displayedTranscript().isNotEmpty())
            assertNotNull(entry.displayedTranscript().first().speakerId)
            val correction = entry.processingState!!.stages.first { it.stage == ProcessingStage.CORRECT_TEXT }
            assertEquals(StageStatus.Skipped, correction.status)
        } finally {
            if (AudioProcessingController.state.value.phase == AudioProcessingPhase.RUNNING) {
                AudioProcessingController.cancel(context)
            }
            audio.delete()
        }
        Unit
    }
}
