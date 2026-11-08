package org.cfm.catscriptor.recording

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.cfm.catscriptor.audio.AndroidAudioDecoder
import org.cfm.catscriptor.domain.app.LiveLine
import org.cfm.catscriptor.domain.pipeline.AudioInput
import org.junit.Assert.*
import org.junit.Test

class LiveSpeechPreviewTest {
    @Test fun bundledModelsRecognizeBoundedPcmPreview() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val storage = AndroidRecordingStorage(context)
        val file = storage.create()
        val ready = CompletableDeferred<Unit>()
        val recognized = CompletableDeferred<LiveLine>()
        val preview = LiveSpeechPreview(context) { line, status, _ ->
            if (status == "Ожидание речи…") ready.complete(Unit)
            if (line != null) recognized.complete(line)
        }
        try {
            instrumentation.context.assets.open("sherpa-example.wav").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            preview.enable(true)
            withTimeout(180_000) { ready.await() }
            AndroidAudioDecoder(context).decode(AudioInput(storage.uri(file))).use { pcm ->
                var position = 0L
                while (position < pcm.sampleCount) {
                    val data = pcm.read(position, 512)
                    if (data.isEmpty()) break
                    preview.offer(position, ShortArray(data.size) { (data[it] * 32767).toInt().toShort() }, data.size)
                    position += data.size
                    delay(32)
                }
                repeat(32) {
                    preview.offer(position, ShortArray(512), 512)
                    position += 512
                    delay(32)
                }
            }
            val line = withTimeout(180_000) { recognized.await() }
            assertTrue(line.text.isNotBlank())
            assertEquals("", line.speakerId)
            assertTrue(line.startMs >= 0)
            android.util.Log.i("LivePreviewTest", "Реальный ASR вернул ${line.text.length} символов, start_ms=${line.startMs}")
            preview.enable(false)
        } finally { preview.close(); file.delete() }
    }
}
