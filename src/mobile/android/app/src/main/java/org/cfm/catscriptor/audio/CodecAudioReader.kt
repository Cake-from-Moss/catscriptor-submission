package org.cfm.catscriptor.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Ошибка локального импорта; причина сохраняется для диагностики без содержимого записи. */
class AudioImportException(val reason: Reason, message: String, cause: Throwable? = null) :
    IOException(message, cause) {
    enum class Reason { INVALID_URI, UNSUPPORTED_FORMAT, INVALID_AUDIO, ACCESS, STORAGE, CODEC_UNAVAILABLE, NO_PROGRESS }
}

/**
 * Ограниченное чтение PCM; метаданные delay/padding передаются кодеку без изменения.
 * Повторный trim намеренно не применяется: поддержка gapless требует проверки на целевом устройстве.
 * Валидный префикс усечённого compressed-файла платформа не всегда отличает от полной записи.
 */
internal object CodecAudioReader {
    suspend fun decode(file: File, consume: suspend (FloatArray, Int, Int) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var failure: Throwable? = null
        try {
            try { extractor.setDataSource(file.path) } catch (error: IOException) {
                throw AudioImportException(AudioImportException.Reason.INVALID_AUDIO, "Не удалось распознать аудиоконтейнер: файл повреждён или формат не поддерживается", error)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AudioImportException(AudioImportException.Reason.UNSUPPORTED_FORMAT, "В документе нет аудиодорожки")
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            if (mime !in setOf("audio/mpeg", "audio/mp4a-latm", "audio/vorbis", "audio/opus")) {
                throw AudioImportException(AudioImportException.Reason.UNSUPPORTED_FORMAT, "Формат аудио не поддерживается: $mime")
            }
            extractor.selectTrack(track)
            val decoder = try { MediaCodec.createDecoderByType(mime) }
            catch (error: IOException) {
                throw AudioImportException(AudioImportException.Reason.CODEC_UNAVAILABLE, "На устройстве недоступен декодер $mime", error)
            } catch (error: IllegalArgumentException) {
                throw AudioImportException(AudioImportException.Reason.CODEC_UNAVAILABLE, "На устройстве нет подходящего декодера $mime", error)
            }
            codec = decoder
            // Исходный MediaFormat сохраняет encoder-delay/padding; повторно PCM не обрезаем.
            decoder.configure(format, null, null, 0)
            decoder.start()
            var outputFormat = format
            var inputEnded = false
            var lastProgress = SystemClock.elapsedRealtime()
            val info = MediaCodec.BufferInfo()
            while (true) {
                currentCoroutineContext().ensureActive()
                var progressed = false
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index) ?: error("Нет входного буфера кодека")
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            val flags = extractor.sampleFlags
                            if (flags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                                throw AudioImportException(AudioImportException.Reason.UNSUPPORTED_FORMAT, "Зашифрованные аудиодорожки не поддерживаются")
                            }
                            // PARTIAL_FRAME — граница access unit, а не признак повреждения записи.
                            val codecFlags = if (flags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
                                MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                            } else 0
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, codecFlags)
                            extractor.advance()
                        }
                        progressed = true
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputFormat = decoder.outputFormat
                    progressed = true
                } else if (index >= 0) {
                    var outputFailure: Throwable? = null
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val buffer = decoder.getOutputBuffer(index) ?: error("Нет выходного буфера кодека")
                            buffer.clear()
                            buffer.limit(info.offset + info.size)
                            buffer.position(info.offset)
                            buffer.order(ByteOrder.nativeOrder())
                            val channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val rate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else AudioFormat.ENCODING_PCM_16BIT
                            val bytes = when (encoding) {
                                AudioFormat.ENCODING_PCM_16BIT -> 2
                                AudioFormat.ENCODING_PCM_FLOAT -> 4
                                else -> throw AudioImportException(AudioImportException.Reason.UNSUPPORTED_FORMAT, "Кодек вернул неподдерживаемый PCM: $encoding")
                            }
                            if (channels !in 1..256 || rate !in 1..768000 || info.size % (channels * bytes) != 0) {
                                throw AudioImportException(AudioImportException.Reason.INVALID_AUDIO, "Некорректный формат PCM")
                            }
                            while (buffer.hasRemaining()) {
                                currentCoroutineContext().ensureActive()
                                val count = minOf(buffer.remaining() / (bytes * channels), 4096) * channels
                                val samples = FloatArray(count) {
                                    if (bytes == 2) buffer.short / 32768f else buffer.float
                                }
                                consume(samples, rate, channels)
                            }
                        }
                    } catch (error: Throwable) {
                        outputFailure = error
                        throw error
                    } finally {
                        cleanupAudio(outputFailure, { decoder.releaseOutputBuffer(index, false) })
                    }
                    progressed = true
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                if (progressed) lastProgress = SystemClock.elapsedRealtime()
                if (SystemClock.elapsedRealtime() - lastProgress > 30_000) {
                    throw AudioImportException(AudioImportException.Reason.NO_PROGRESS, "Аудиодекодер не отвечает более 30 секунд")
                }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            cleanupAudio(failure, { codec?.release() }, { extractor.release() })
        }
    }
}
