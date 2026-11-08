package org.cfm.catscriptor.recording

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import org.cfm.catscriptor.domain.app.LiveLine
import org.cfm.catscriptor.domain.pipeline.*
import org.cfm.catscriptor.native.models.*

/** Необязательная ветка preview: переполнение никогда не блокирует запись WAV. */
internal class LiveSpeechPreview(
    private val context: Context,
    private val update: (LiveLine?, String, Boolean) -> Unit,
) {
    private data class Block(val start: Long, val samples: FloatArray, val generation: Long)
    private data class Phrase(val start: Long, val samples: FloatArray)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val enabled = MutableStateFlow(false)
    @Volatile private var input: Channel<Block>? = null
    @Volatile private var generation = 0L
    private val sessionId = UUID.randomUUID().toString()
    private val job = scope.launch {
        enabled.collectLatest { active ->
            if (!active) return@collectLatest
            try {
                coroutineScope {
                    val worker = launch {
                        val acquired = ModelExecutionGate.preview(currentCoroutineContext().job) { recognize() }
                        if (!acquired) update(null, "Live недоступен: идёт финальная обработка. Запись продолжается.", false)
                    }
                    worker.join()
                    if (worker.isCancelled) update(null, "Live остановлен для финальной обработки. Запись продолжается.", false)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                android.util.Log.e("LiveASR", "Ошибка Live-распознавания", error)
                update(null, "Ошибка Live-ASR. Аудио сохраняется; выключите и включите Live для повтора.", false)
            }
        }
    }

    fun enable(value: Boolean) {
        if (enabled.value == value) return
        input = null
        enabled.value = value
        if (!value) update(null, "Live выключен", false)
    }

    fun offer(start: Long, samples: ShortArray, count: Int) {
        val channel = input ?: return
        val data = FloatArray(count) { samples[it] / 32768f }
        if (!channel.trySend(Block(start, data, generation)).isSuccess)
            update(null, "Live не успевает: часть предпросмотра пропущена. Полное аудио сохранено.", false)
    }

    /** Отдельный разрыв при паузе, даже если абсолютный счётчик сэмплов не изменился. */
    fun pause() { generation++ }

    suspend fun close() {
        input = null
        job.cancelAndJoin()
        scope.cancel()
    }

    private suspend fun recognize() = coroutineScope {
        update(null, "Подготовка Live-моделей…", false)
        val assets = context.assets
        val store = BundledModelStore(File(context.noBackupFilesDir, "models")) { assets.open(it) }
        val coroutine = currentCoroutineContext()
        val spec = assets.open("vad-manifest.properties").use { BundledModelSpec.readVad(it) }
        val directory = store.prepare(spec) { coroutine.ensureActive() }
        coroutine.ensureActive()
        val vad = Vad(config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(model = File(directory, "silero_vad.onnx").path, windowSize = 512),
            sampleRate = 16000, numThreads = 1, provider = "cpu",
        ))
        val asr = SherpaTranscriptionBackend(context)
        val frames = Channel<Block>(32)
        val phrases = Channel<Phrase>(2)
        var decoder: Job? = null
        try {
            input = frames
            update(null, "Ожидание речи…", false)
            decoder = launch {
                for (phrase in phrases) {
                    ensureActive()
                    update(null, "Распознавание…", true)
                    val started = android.os.SystemClock.elapsedRealtime()
                    val audio = object : PcmAudioSource {
                        override val sampleCount = phrase.samples.size.toLong()
                        override suspend fun read(startSample: Long, maxSamples: Int): FloatArray =
                            phrase.samples.copyOfRange(startSample.toInt(), minOf(phrase.samples.size, startSample.toInt() + maxSamples))
                        override fun close() = Unit
                    }
                    val result = asr.transcribe(audio, SpeechSegment(TimeRange(0, (audio.sampleCount + 15) / 16)))
                    ensureActive()
                    val line = result.text.trim().takeIf { it.isNotEmpty() }?.let {
                        LiveLine("$sessionId:${phrase.start}", "", phrase.start / 16, it, true)
                    }
                    android.util.Log.i("LiveASR", "start_ms=${phrase.start / 16} samples=${phrase.samples.size} asr_ms=${android.os.SystemClock.elapsedRealtime() - started}")
                    update(line, "Ожидание речи…", false)
                }
            }
            val segmenter = LivePcmSegmenter(
                probability = { vad.compute(it) }, resetVad = { vad.reset() },
                onPhrase = { start, data ->
                    if (!phrases.trySend(Phrase(start, data)).isSuccess)
                        update(null, "Live не успевает: фрагмент предпросмотра пропущен. Полное аудио сохранено.", false)
                },
            )
            var previousGeneration = -1L
            for (frame in frames) {
                ensureActive()
                if (previousGeneration != frame.generation) segmenter.accept(-1, FloatArray(0))
                previousGeneration = frame.generation
                segmenter.accept(frame.start, frame.samples)
            }
            decoder.cancelAndJoin()
        } finally {
            input = null
            frames.close()
            phrases.close()
            // coroutineScope сначала ждёт своих детей: JNI нельзя закрывать параллельно decode.
            withContext(NonCancellable) { decoder?.cancelAndJoin() }
            asr.close()
            vad.release()
        }
    }
}
