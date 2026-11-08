package org.cfm.catscriptor.export

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.cfm.catscriptor.MainActivity
import org.cfm.catscriptor.app.ProductionAppGraph
import org.cfm.catscriptor.audio.background.AudioProcessingController
import org.cfm.catscriptor.audio.background.AudioProcessingState
import org.cfm.catscriptor.domain.app.ExportFormat
import org.cfm.catscriptor.domain.app.ExportOptions
import org.cfm.catscriptor.domain.app.ImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Сквозная проверка основного графа на настоящих моделях: русская запись → готовая расшифровка →
 * экспорт строгого JSON, ZIP с аудио, файлы для share sheet и воспроизведение исходного файла.
 * Раньше ProductionAppGraph подключал заглушки, и экспорт создавал пустой файл, а плеер был недоступен.
 */
@RunWith(AndroidJUnit4::class)
class ExportPlaybackEndToEndTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val authority = "${context.packageName}.recordings"

    private fun recordingFile(name: String) = File(context.filesDir, "recordings/$name").apply { parentFile!!.mkdirs() }

    @Test fun readyTranscriptIsExportedAndRecordingPlays() = runBlocking<Unit> {
        AudioProcessingController.publish(AudioProcessingState())
        val source = recordingFile("e2e-source.wav")
        instrumentation.context.assets.open("sherpa-example.wav").use { input -> source.outputStream().use { input.copyTo(it) } }
        val sourceUri = FileProvider.getUriForFile(context, authority, source).toString()

        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { it.setShowWhenLocked(true) }
            // Граф создаётся в главном потоке: плеер и MediaPlayer однопоточные.
            val graph = withContext(Dispatchers.Main) { ProductionAppGraph(context) }
            val id = (graph.processing.importFile(sourceUri, "Пример.wav") as ImportResult.Accepted).recordingId
            val document = withTimeout(600_000) { graph.recordings.observeTranscript(id).first { it?.asrDraft == null && it != null }!! }

            // 1. Строгий JSON через SAF-подобный content URI.
            val jsonFile = recordingFile("e2e-export.json").apply { writeText("") }
            val jsonOptions = ExportOptions(ExportFormat.JSON, useSpeakerNames = true)
            graph.export.saveTo(FileProvider.getUriForFile(context, authority, jsonFile).toString(), id, jsonOptions)
            val root = JsonParser.parseString(jsonFile.readText()).asJsonObject
            assertEquals(setOf("transcript"), root.keySet())
            val items = root.getAsJsonArray("transcript")
            assertEquals(document.lines.size, items.size())
            assertTrue("в расшифровке должна быть речь", items.size() > 0)
            var previousStart = -1L
            items.forEach { element ->
                val item = element.asJsonObject
                assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), item.keySet())
                assertTrue(item["speaker_id"].asString.matches(Regex("speaker_[1-9][0-9]*")))
                val start = item["start_ms"].asLong
                assertTrue(start < item["end_ms"].asLong && start >= previousStart)
                previousStart = start
            }

            // 2. Сохранение с аудио — ZIP с расшифровкой и байт-в-байт исходным файлом.
            val zipFile = recordingFile("e2e-export.zip").apply { writeText("") }
            val audioOptions = jsonOptions.copy(includeAudio = true)
            assertEquals("Пример.zip", graph.export.saveTarget(id, document.title, audioOptions).fileName)
            graph.export.saveTo(FileProvider.getUriForFile(context, authority, zipFile).toString(), id, audioOptions)
            val entries = HashMap<String, ByteArray>()
            ZipInputStream(zipFile.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes()
                }
            }
            assertEquals(setOf("Пример.json", "Пример.wav"), entries.keys)
            assertTrue(entries.getValue("Пример.wav").contentEquals(source.readBytes()))
            assertEquals(jsonFile.readText(), String(entries.getValue("Пример.json")))

            // 3. Share: два файла, оба читаются через FileProvider.
            val share = graph.export.prepareShare(id, audioOptions)
            assertEquals(1, share.extraContentUris.size)
            (listOf(share.contentUri) + share.extraContentUris).forEach { uri ->
                context.contentResolver.openInputStream(uri.toUri())!!.use { assertTrue(it.read() >= 0) }
            }

            // 4. Плеер играет исходный файл, позиция идёт вперёд.
            withContext(Dispatchers.Main) { graph.playback.load(id) }
            withTimeout(10_000) { graph.playback.state.first { it.isAvailable } }
            assertTrue(graph.playback.state.value.durationMs > 1_000)
            withContext(Dispatchers.Main) { graph.playback.play() }
            delay(1_500)
            val position = graph.playback.state.value.positionMs
            assertTrue("позиция должна расти, а она $position", position > 300)
            withContext(Dispatchers.Main) {
                graph.playback.pause()
                graph.playback.seekTo(0)
                graph.playback.release(id)
            }
            assertTrue(!graph.playback.state.value.isAvailable)
        }
        listOf("e2e-source.wav", "e2e-export.json", "e2e-export.zip").forEach { recordingFile(it).delete() }
    }
}
