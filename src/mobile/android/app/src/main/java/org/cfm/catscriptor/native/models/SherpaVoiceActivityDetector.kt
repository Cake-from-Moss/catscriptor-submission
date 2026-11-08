package org.cfm.catscriptor.native.models

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cfm.catscriptor.audio.speech.SileroVadSettings
import org.cfm.catscriptor.audio.speech.VadSegmentation
import org.cfm.catscriptor.domain.pipeline.PcmAudioSource
import org.cfm.catscriptor.domain.pipeline.SpeechSegment
import org.cfm.catscriptor.domain.pipeline.VoiceActivityDetector

/**
 * Silero v4 через sherpa compute(): модель хранит только рекуррентное состояние.
 * Нативная очередь с копиями PCM не используется; границы выделяются в Kotlin.
 * Закрытие ждёт текущий JNI-вызов, но не освобождает указатель во время инференса.
 */
class SherpaVoiceActivityDetector(
    context: Context,
    private val settings: SileroVadSettings = SileroVadSettings(),
) : VoiceActivityDetector {
    private val assets = context.applicationContext.assets
    private val store = BundledModelStore(File(context.noBackupFilesDir, "models")) { assets.open(it) }
    private val operation = Mutex()
    private val lock = Any()
    private var engine: Vad? = null
    private var closed = false

    override suspend fun detect(audio: PcmAudioSource): List<SpeechSegment> = withContext(Dispatchers.IO) {
        operation.withLock {
            val coroutine = currentCoroutineContext()
            try {
                synchronized(lock) {
                    check(!closed) { "VAD уже закрыт" }
                    coroutine.ensureActive()
                    if (engine == null) {
                        val spec = assets.open("vad-manifest.properties").use { BundledModelSpec.readVad(it) }
                        val directory = store.prepare(spec) { coroutine.ensureActive() }
                        coroutine.ensureActive()
                        engine = Vad(config = VadModelConfig(
                            sileroVadModelConfig = SileroVadModelConfig(
                                model = File(directory, "silero_vad.onnx").path,
                                windowSize = 512,
                            ),
                            sampleRate = PcmAudioSource.SAMPLE_RATE_HZ,
                            numThreads = 1,
                            provider = "cpu",
                        ))
                    }
                    coroutine.ensureActive()
                    engine!!.reset()
                }
                require(audio.sampleCount >= 0)
                val segmentation = VadSegmentation(settings)
                val window = FloatArray(512)
                var filled = 0
                var offset = 0L
                fun infer(valid: Int) {
                    coroutine.ensureActive()
                    val probability = synchronized(lock) {
                        check(!closed) { "VAD закрыт во время обработки" }
                        // Цифровая тишина не должна создавать речевые интервалы даже после речи.
                        val result = engine!!.compute(window)
                        if (window.all { it == 0f }) 0f else result
                    }
                    coroutine.ensureActive()
                    segmentation.accept(probability, valid)
                }
                while (offset < audio.sampleCount) {
                    coroutine.ensureActive()
                    val requested = minOf(8192L, audio.sampleCount - offset).toInt()
                    val samples = audio.read(offset, requested)
                    check(samples.isNotEmpty() && samples.size <= requested) { "Нарушен контракт чтения PCM" }
                    require(samples.all { it.isFinite() && it in -1f..1f }) { "Некорректный PCM" }
                    for (sample in samples) {
                        window[filled++] = sample
                        if (filled == window.size) {
                            infer(filled)
                            filled = 0
                        }
                    }
                    offset += samples.size
                }
                if (filled > 0) {
                    window.fill(0f, filled)
                    infer(filled)
                }
                coroutine.ensureActive()
                segmentation.finish()
            } catch (error: Throwable) {
                close()
                throw error
            }
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            val previous = engine
            engine = null
            previous?.release()
        }
    }
}
