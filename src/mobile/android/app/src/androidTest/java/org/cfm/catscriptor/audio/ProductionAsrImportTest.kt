package org.cfm.catscriptor.audio

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cfm.catscriptor.MainActivity
import org.cfm.catscriptor.app.ProductionAppGraph
import org.cfm.catscriptor.audio.background.AudioProcessingController
import org.cfm.catscriptor.audio.background.AudioProcessingPhase
import org.cfm.catscriptor.audio.background.AudioProcessingState
import org.cfm.catscriptor.domain.app.*
import org.cfm.catscriptor.domain.pipeline.AudioInput
import org.cfm.catscriptor.native.models.SherpaVoiceActivityDetector
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionAsrImportTest {
    @org.junit.Before fun resetControllerState() {
        // Синглтон процесса приложения: сбрасываем перед прогоном, чтобы не наследовать
        // терминальный статус чужого instrument-теста.
        AudioProcessingController.publish(AudioProcessingState())
    }

    @Test fun importRealRussianSpeechProducesReadyTranscript() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = "content://org.cfm.catscriptor.audio.service.test/speech"
        // Независимый реальный VAD подтверждает, что в записи есть речь.
        val expected = AndroidAudioDecoder(context).decode(AudioInput(uri)).use { pcm ->
            SherpaVoiceActivityDetector(context).use { it.detect(pcm).map { segment -> segment.time } }
        }
        assertTrue(expected.isNotEmpty())
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { it.setShowWhenLocked(true) }
            val graph = ProductionAppGraph(context)
            try {
                val result = graph.processing.importFile(uri, "Русская речь.wav") as ImportResult.Accepted
                // Полный путь: VAD → диаризация → ASR → LLM-коррекция; на устройстве занимает минуты.
                val document = withTimeout(600_000) {
                    graph.recordings.observeTranscript(result.recordingId).first { it != null }!!
                }
                assertEquals("Русская речь", document.title)
                assertTrue(document.durationMs > 0)
                // Финальная расшифровка: реплики со спикерами и исправленным текстом.
                assertNull(document.asrDraft)
                assertTrue(document.lines.isNotEmpty())
                assertTrue(document.speakers.isNotEmpty())
                assertTrue(document.lines.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
                assertTrue(document.lines.all { it.startMs in 0..document.durationMs && it.startMs < it.endMs })
                assertTrue(document.lines.any { line -> line.text.any { it in 'а'..'я' || it in 'А'..'Я' } })
                // Служебные токены модели не должны утекать в финальный текст.
                assertTrue(document.lines.none { it.text.contains("<|") })
                assertTrue(document.speakers.all { it.shareFraction in 0f..1f })
                assertEquals(RecordingStatus.Ready,
                    graph.recordings.observe().first().single { it.id == result.recordingId }.status)
                assertFalse(graph.processing.observeJobs().first().any { it.recordingId == result.recordingId })
                val state = AudioProcessingController.state.value
                assertEquals(AudioProcessingPhase.COMPLETED, state.phase)
                for (stage in ProcessingStage.entries) {
                    assertTrue(state.stages.single { it.stage == stage }.status is StageStatus.Done)
                }
                // Готовая расшифровка экспортируется и превращается в pipeline.Transcript.
                for (format in ExportFormat.entries) {
                    val rendered = TranscriptRendering.render(document, ExportOptions(format, false)) { "Спикер $it" }
                    assertTrue(rendered.isNotBlank())
                }
                val transcript = document.toPipelineTranscript()
                assertEquals(document.lines.size, transcript.transcript.size)
            } finally {
                AudioProcessingController.cancel(context)
                withTimeout(180_000) { AudioProcessingController.state.first { it.phase != AudioProcessingPhase.RUNNING } }
            }
        }
    }
}
