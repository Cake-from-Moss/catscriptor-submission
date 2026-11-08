package org.cfm.catscriptor.audio

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.cfm.catscriptor.audio.core.FilePcmAudioSource
import org.cfm.catscriptor.audio.core.StreamingSincResampler
import org.cfm.catscriptor.audio.core.WavReader
import org.cfm.catscriptor.domain.pipeline.AudioDecoder
import org.cfm.catscriptor.domain.pipeline.AudioInput
import org.cfm.catscriptor.domain.pipeline.PcmAudioSource
import androidx.core.net.toUri

/**
 * Копирует SAF-документ в приватный seekable-файл и возвращает принадлежащий вызывающему PCM.
 * EXTRA_LOCAL_ONLY исключает облачные документы из стандартного picker, но поведение стороннего
 * ContentProvider приложение контролировать не может. Сетевые URI здесь никогда не открываются.
 */
class AndroidAudioDecoder(context: Context) : AudioDecoder {
    private val resolver = context.applicationContext.contentResolver
    private val cache = context.applicationContext.cacheDir

    override suspend fun decode(input: AudioInput): PcmAudioSource {
        var result: FilePcmAudioSource? = null
        var session: AudioCacheSession? = null
        var delivered = false
        var failure: Throwable? = null
        try {
            val source = withContext(Dispatchers.IO) {
                val uri = input.documentId.toUri()
                if (uri.scheme != "content") {
                    throw AudioImportException(AudioImportException.Reason.INVALID_URI, "Нужен локальный content URI из SAF")
                }
                val mime = resolver.getType(uri)
                if (mime != null && !mime.startsWith("audio/") && mime !in setOf("application/ogg", "application/octet-stream")) {
                    throw AudioImportException(AudioImportException.Reason.UNSUPPORTED_FORMAT, "Выбранный документ не является аудио")
                }
                val owned = audioIo(AudioImportException.Reason.STORAGE, "Не удалось создать временное хранилище аудио") { AudioCacheSession.create(cache) }
                session = owned
                val compressed = File(owned.directory, "input")
                val pcm = File(owned.directory, "output.f32")
                audioIo(AudioImportException.Reason.ACCESS, "Ошибка открытия или закрытия выбранного аудиодокумента") { resolver.openInputStream(uri)?.use { stream ->
                    audioIo(AudioImportException.Reason.STORAGE, "Ошибка создания, записи или закрытия копии аудио: проверьте свободное место") { compressed.outputStream().use { output ->
                        val block = ByteArray(64 * 1024)
                        var emptyReads = 0
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = audioIo(AudioImportException.Reason.ACCESS, "Ошибка чтения выбранного аудиодокумента") { stream.read(block) }
                            if (count < 0) break
                            if (count == 0) {
                                if (++emptyReads > 100) throw AudioImportException(AudioImportException.Reason.NO_PROGRESS, "Поставщик документа не возвращает данные")
                            } else {
                                emptyReads = 0
                                audioIo(AudioImportException.Reason.STORAGE, "Не удалось записать копию аудио: проверьте свободное место") { output.write(block, 0, count) }
                            }
                        }
                    } }
                } } ?: throw AudioImportException(AudioImportException.Reason.ACCESS, "Не удалось открыть аудиодокумент")
                audioIo(AudioImportException.Reason.STORAGE, "Ошибка чтения кэша или записи PCM: проверьте свободное место") { decodeFile(compressed, pcm) }
                currentCoroutineContext().ensureActive()
                if (!compressed.delete()) throw AudioImportException(AudioImportException.Reason.STORAGE, "Не удалось удалить временную копию аудио")
                audioIo(AudioImportException.Reason.STORAGE, "Не удалось открыть временный PCM") { FilePcmAudioSource(pcm) }.also { result = it }
            }
            delivered = true
            // Закрытие источника удаляет PCM; оболочка также удаляет только свой пустой каталог.
            return object : PcmAudioSource by source {
                override fun close() {
                    cleanupAudio(null, { source.close() }, { session?.close() })
                }
            }
        } catch (error: Exception) {
            val mapped = when (error) {
                is CancellationException, is AudioImportException -> error
                is SecurityException -> AudioImportException(AudioImportException.Reason.ACCESS, "Нет разрешения на чтение аудиодокумента", error)
                else -> AudioImportException(AudioImportException.Reason.INVALID_AUDIO, "Не удалось декодировать аудиодокумент", error)
            }
            failure = mapped
            throw mapped
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (!delivered) {
                cleanupAudio(failure, { result?.close() }, { session?.close() })
            }
        }
    }

    private suspend fun decodeFile(input: File, output: File) {
        val wav = input.inputStream().use { stream ->
            val header = ByteArray(12)
            val count = stream.read(header)
            val container = String(header, 0, 4, Charsets.US_ASCII)
            if (container == "RF64" || container == "RIFX") throw AudioImportException(AudioImportException.Reason.UNSUPPORTED_FORMAT, "Контейнер WAV $container не поддерживается")
            count == 12 && container == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
        }
        if (wav) {
            try { decodeWav(input, output) } catch (error: java.io.EOFException) {
                throw AudioImportException(AudioImportException.Reason.INVALID_AUDIO, "WAV обрезан: недостаточно аудиоданных", error)
            } catch (error: IllegalArgumentException) {
                if (error.message?.contains("Unsupported", ignoreCase = true) == true) {
                    throw AudioImportException(AudioImportException.Reason.UNSUPPORTED_FORMAT, "Эта разновидность WAV не поддерживается", error)
                }
                throw error
            }
            return
        }
        FileOutputStream(output).buffered().use { sink ->
            val context = currentCoroutineContext()
            var rate = 0
            var resampler: StreamingSincResampler? = null
            CodecAudioReader.decode(input) { samples, sampleRate, channels ->
                currentCoroutineContext().ensureActive()
                if (resampler == null) {
                    rate = sampleRate
                    resampler = StreamingSincResampler(rate) { block ->
                        context.ensureActive()
                        val bytes = ByteBuffer.allocate(block.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                        block.forEach { bytes.putFloat(it) }
                        sink.write(bytes.array())
                    }
                }
                if (rate != sampleRate) throw AudioImportException(AudioImportException.Reason.UNSUPPORTED_FORMAT, "Частота аудио меняется внутри дорожки")
                val mono = FloatArray(samples.size / channels) { frame ->
                    var sum = 0.0
                    repeat(channels) { channel ->
                        val sample = samples[frame * channels + channel]
                        require(sample.isFinite()) { "Некорректный отсчёт PCM" }
                        sum += sample.coerceIn(-1f, 1f)
                    }
                    (sum / channels).toFloat()
                }
                resampler!!.accept(mono)
            }
            context.ensureActive()
            resampler?.finish()
        }
        if (output.length() == 0L) throw AudioImportException(AudioImportException.Reason.INVALID_AUDIO, "Аудиодорожка пуста")
    }

    private suspend fun decodeWav(input: File, output: File) {
        val context = currentCoroutineContext()
        FileOutputStream(output).buffered().use { sink ->
            var resampler: StreamingSincResampler? = null
            WavReader.decode(input, { format ->
                resampler = StreamingSincResampler(format.sampleRate) { block ->
                    context.ensureActive()
                    val bytes = ByteBuffer.allocate(block.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    block.forEach { bytes.putFloat(it) }
                    sink.write(bytes.array())
                }
            }, { samples ->
                context.ensureActive()
                resampler!!.accept(samples)
            }, checkCancellation = { context.ensureActive() })
            resampler?.finish()
        }
        if (output.length() == 0L) throw AudioImportException(AudioImportException.Reason.INVALID_AUDIO, "Аудиодорожка пуста")
    }
}
