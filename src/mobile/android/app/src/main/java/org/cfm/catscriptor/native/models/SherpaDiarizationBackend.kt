package org.cfm.catscriptor.native.models

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cfm.catscriptor.domain.diarization.EmbeddingDiarizer
import org.cfm.catscriptor.domain.diarization.EmbeddingDiarizerSettings
import org.cfm.catscriptor.domain.diarization.SpeakerEmbedder
import org.cfm.catscriptor.domain.pipeline.DiarizationBackend
import org.cfm.catscriptor.domain.pipeline.PcmAudioSource
import org.cfm.catscriptor.domain.pipeline.SpeakerSegment
import org.cfm.catscriptor.domain.pipeline.SpeechSegment

/**
 * Владелец нативного экстрактора эмбеддингов CAM++ (3D-Speaker) из sherpa-onnx.
 * Модель загружается лениво при первом окне. [close] ждёт текущий JNI-вызов,
 * поэтому его нельзя вызывать из UI-потока.
 */
internal class SherpaEmbeddingResource(context: Context, private val numThreads: Int) : ModelResource {
    private val assets = context.applicationContext.assets
    private val store = BundledModelStore(File(context.noBackupFilesDir, "models")) { assets.open(it) }
    private val lock = Any()
    private var extractor: SpeakerEmbeddingExtractor? = null
    private var closed = false

    fun embed(samples: FloatArray, cancelCheck: () -> Unit): FloatArray? = synchronized(lock) {
        check(!closed) { "Диаризация уже закрыта" }
        cancelCheck()
        val created = extractor ?: run {
            val spec = assets.open(MANIFEST).use { BundledModelSpec.readDiarization(it) }
            val directory = store.prepare(spec, cancelCheck)
            SpeakerEmbeddingExtractor(
                config = SpeakerEmbeddingExtractorConfig(
                    model = File(directory, "embedding.onnx").path,
                    numThreads = numThreads,
                    provider = "cpu",
                ),
            )
        }.also { extractor = it }
        val stream = created.createStream()
        try {
            stream.acceptWaveform(samples, PcmAudioSource.SAMPLE_RATE_HZ)
            stream.inputFinished()
            if (!created.isReady(stream)) return null
            created.compute(stream).takeIf { vector -> vector.isNotEmpty() && vector.all { it.isFinite() } }
        } finally {
            stream.release()
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            extractor?.release()
            extractor = null
        }
    }

    private companion object {
        const val MANIFEST = "diarization-manifest.properties"
    }
}

/**
 * Офлайн-диаризация: эмбеддинги CAM++ по окнам речи VAD + спектральная кластеризация
 * ([EmbeddingDiarizer]). Сегментация pyannote/кластеризация sherpa не используются: на русской
 * записи с двумя голосами они находили одного спикера, а на записи с четырьмя — восемь
 * (см. docs/diarization.md). После ошибки или отмены backend закрывается, для повтора нужен
 * новый экземпляр. [PcmAudioSource] не закрывается.
 */
class SherpaDiarizationBackend internal constructor(
    private val resource: SherpaEmbeddingResource,
    private val settings: EmbeddingDiarizerSettings,
    private val targetSpeakers: Int?,
) : DiarizationBackend {
    /** [targetSpeakers] — число спикеров из настроек; null — автоопределение. */
    constructor(
        context: Context,
        targetSpeakers: Int? = null,
        settings: EmbeddingDiarizerSettings = EmbeddingDiarizerSettings(),
        numThreads: Int = 2,
    ) : this(SherpaEmbeddingResource(context, numThreads), settings, targetSpeakers)

    private val operation = Mutex()
    private val lifecycle = Any()
    private var closed = false

    override suspend fun diarize(audio: PcmAudioSource, speech: List<SpeechSegment>): List<SpeakerSegment> =
        try {
            withContext(Dispatchers.IO) {
                operation.withLock {
                    checkOpen()
                    currentCoroutineContext().ensureActive()
                    EmbeddingDiarizer(embedderProvider = { embedder() }, settings = settings, targetSpeakers = targetSpeakers)
                        .diarize(audio, speech)
                }
            }
        } catch (error: Throwable) {
            close()
            throw error
        }

    private fun embedder() = object : SpeakerEmbedder {
        override suspend fun embed(samples: FloatArray): FloatArray? {
            val context = currentCoroutineContext()
            return resource.embed(samples) { context.ensureActive() }
        }
    }

    private fun checkOpen() = synchronized(lifecycle) { check(!closed) { "Диаризация уже закрыта" } }

    override fun close() = synchronized(lifecycle) {
        if (!closed) {
            closed = true
            resource.close()
        }
    }
}
