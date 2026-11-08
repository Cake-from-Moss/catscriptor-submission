package org.cfm.catscriptor.native.models

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Точки эмиссии токенов, не пословные интервалы и не реализация TimedWord. */
data class SherpaDiagnosticResult(
    val text: String,
    val tokens: List<String>,
    val timestampsSeconds: List<Float>,
    val ctcPosterior: CtcPosterior? = null,
)

/**
 * Ленивый владелец официального recognizer для одного этапа.
 * JNI-вызов decode синхронный: отмена проверяется до и после него, ресурсы не освобождаются
 * параллельно с вызовом. [close] вызывать вне UI-потока: он может ожидать текущий decode.
 */
class SherpaModelResource(context: Context, private val numThreads: Int = 2) : ModelResource {
    private val assets = context.applicationContext.assets
    private val store = BundledModelStore(File(context.noBackupFilesDir, "models")) { assets.open(it) }
    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var closed = false

    init {
        require(numThreads in 1..8) { "Число потоков должно быть от 1 до 8" }
    }

    /** Одна порция 16 кГц mono PCM; posterior запрашивается только для экспериментальной диагностики. */
    suspend fun diagnose(samples: FloatArray, includeCtcFrames: Boolean = false): SherpaDiagnosticResult = try {
        withContext(Dispatchers.IO) {
        require(samples.isNotEmpty() && samples.size <= 25 * 16000) {
            "Диагностика принимает от одного сэмпла до 25 секунд PCM"
        }
        require(samples.all { it.isFinite() && it in -1f..1f }) { "Некорректный PCM" }
        val coroutine = currentCoroutineContext()
        synchronized(lock) {
            check(!closed) { "Ресурс модели уже закрыт" }
            coroutine.ensureActive()
            val engine = recognizer ?: run {
                val spec = assets.open("model-manifest.properties").use { BundledModelSpec.read(it) }
                val directory = store.prepare(spec) { coroutine.ensureActive() }
                coroutine.ensureActive()
                val created = OfflineRecognizer(
                    config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 64),
                        modelConfig = OfflineModelConfig(
                            nemo = OfflineNemoEncDecCtcModelConfig(File(directory, "model.int8.onnx").path),
                            tokens = File(directory, "tokens.txt").path,
                            modelType = "nemo_ctc",
                            provider = "cpu",
                            numThreads = numThreads,
                        ),
                    ),
                )
                recognizer = created
                created
            }
            coroutine.ensureActive()
            val stream = engine.createStream()
            try {
                if (includeCtcFrames) stream.setOption(CtcPosterior.OPTION, "request")
                stream.acceptWaveform(samples, 16000)
                coroutine.ensureActive()
                engine.decode(stream)
                coroutine.ensureActive()
                val result = engine.getResult(stream)
                val posterior = if (includeCtcFrames) CtcPosterior.parse(stream.getOption(CtcPosterior.OPTION)) else null
                coroutine.ensureActive()
                SherpaDiagnosticResult(result.text, result.tokens.toList(), result.timestamps.toList(), posterior)
            } finally {
                stream.release()
            }
        }
        }
    } catch (error: Throwable) {
        close()
        throw error
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            val engine = recognizer
            recognizer = null
            engine?.release()
        }
    }
}
