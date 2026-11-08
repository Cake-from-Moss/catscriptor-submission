package org.cfm.catscriptor.export

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cfm.catscriptor.R
import org.cfm.catscriptor.domain.app.ExportFormat
import org.cfm.catscriptor.domain.app.ExportGateway
import org.cfm.catscriptor.domain.app.ExportOptions
import org.cfm.catscriptor.domain.app.SaveTarget
import org.cfm.catscriptor.domain.app.SharePayload
import org.cfm.catscriptor.domain.app.TranscriptDocument
import org.cfm.catscriptor.domain.app.TranscriptRendering
import org.cfm.catscriptor.domain.app.ZIP_MIME_TYPE
import org.cfm.catscriptor.domain.app.toPipelineTranscript
import org.cfm.catscriptor.domain.pipeline.DefaultTranscriptExporter
import org.cfm.catscriptor.domain.pipeline.TranscriptFormat

/** Исходный аудиофайл записи: content URI и расширение для имени копии (null — неизвестно). */
data class ExportAudio(val uri: String, val extension: String?)

/**
 * Экспорт основной сборки. Строгий JSON пишет [DefaultTranscriptExporter] (схема AGENTS.md),
 * TXT и SRT строит [TranscriptRendering]. С опцией аудио «Сохранить» создаёт ZIP
 * (расшифровка + исходный файл без перекодирования), а «Поделиться» отдаёт два файла.
 *
 * @param document финальная расшифровка записи; ASR-черновик экспортировать нельзя.
 * @param audio исходный аудиофайл записи или null, если его нет.
 * @param authority FileProvider, который отдаёт каталог `cache/exports/` для share sheet.
 */
class FileExportGateway(
    private val context: Context,
    private val document: suspend (recordingId: String) -> TranscriptDocument?,
    private val audio: (recordingId: String) -> ExportAudio?,
    private val authority: String,
) : ExportGateway {
    private val exporter = DefaultTranscriptExporter()

    override suspend fun preview(recordingId: String, options: ExportOptions): String =
        TranscriptRendering.preview(requireDocument(recordingId), options, ::defaultSpeakerName)

    override suspend fun saveTo(documentUri: String, recordingId: String, options: ExportOptions): String {
        val document = requireDocument(recordingId)
        val source = if (options.includeAudio || options.audioOnly) requireAudio(recordingId) else null
        val uri = documentUri.toUri()
        withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("Провайдер не открыл поток для $uri")
            stream.use { output ->
                if (options.audioOnly) {
                    copyAudio(source!!, output)
                } else if (source == null) {
                    writeTranscript(document, options, output)
                } else {
                    writeZip(document, options, source, output)
                }
            }
        }
        return displayName(uri) ?: saveTarget(recordingId, document.title, options).fileName
    }

    override suspend fun prepareShare(recordingId: String, options: ExportOptions): SharePayload {
        val document = requireDocument(recordingId)
        val source = if (options.includeAudio || options.audioOnly) requireAudio(recordingId) else null
        if (options.audioOnly) return shareAudioOnly(document, source!!)
        val files = withContext(Dispatchers.IO) {
            // Каталог чистится перед каждой отправкой: старые файлы в кэше никому не нужны.
            val directory = File(context.cacheDir, SHARE_DIR).apply {
                deleteRecursively()
                mkdirs()
            }
            val transcript = File(directory, suggestedFileName(document.title, options.format))
            transcript.outputStream().use { writeTranscript(document, options, it) }
            val copy = source?.let { audio ->
                File(directory, audioFileName(document.title, audio)).also { target ->
                    target.outputStream().use { copyAudio(audio, it) }
                }
            }
            listOfNotNull(transcript, copy)
        }
        val uris = files.map { FileProvider.getUriForFile(context, authority, it).toString() }
        return SharePayload(
            contentUri = uris.first(),
            // Для нескольких файлов разного типа получатели ждут общий MIME-тип.
            mimeType = if (uris.size == 1) options.format.mimeType else "*/*",
            extraContentUris = uris.drop(1),
        )
    }

    private suspend fun shareAudioOnly(document: TranscriptDocument, source: ExportAudio): SharePayload {
        val file = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, SHARE_DIR).apply {
                deleteRecursively()
                mkdirs()
            }
            File(directory, audioFileName(document.title, source)).also { target ->
                target.outputStream().use { copyAudio(source, it) }
            }
        }
        return SharePayload(
            contentUri = FileProvider.getUriForFile(context, authority, file).toString(),
            mimeType = audioMimeType(source),
        )
    }

    private fun audioMimeType(source: ExportAudio): String =
        source.extension?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase()) }
            ?: "application/octet-stream"

    override fun suggestedFileName(title: String, format: ExportFormat): String =
        "${safeBaseName(title)}.${format.extension}"

    override fun saveTarget(recordingId: String, title: String, options: ExportOptions): SaveTarget =
        if (options.audioOnly) {
            val source = audio(recordingId) ?: ExportAudio(uri = "", extension = null)
            SaveTarget(audioFileName(title, source), audioMimeType(source))
        } else if (options.includeAudio) {
            SaveTarget("${safeBaseName(title)}.zip", ZIP_MIME_TYPE)
        } else {
            SaveTarget(suggestedFileName(title, options.format), options.format.mimeType)
        }

    private suspend fun writeTranscript(document: TranscriptDocument, options: ExportOptions, output: OutputStream) {
        when (options.format) {
            ExportFormat.JSON -> exporter.export(document.toPipelineTranscript(), TranscriptFormat.JSON, output)
            ExportFormat.TXT, ExportFormat.SRT -> {
                output.write(TranscriptRendering.render(document, options, ::defaultSpeakerName).toByteArray(Charsets.UTF_8))
                output.flush()
            }
        }
    }

    private suspend fun writeZip(document: TranscriptDocument, options: ExportOptions, source: ExportAudio, output: OutputStream) {
        val zip = ZipOutputStream(output)
        zip.putNextEntry(ZipEntry(suggestedFileName(document.title, options.format)))
        writeTranscript(document, options, NonClosingStream(zip))
        zip.closeEntry()
        // Аудио уже сжато (m4a/mp3/ogg), повторное сжатие ZIP только тратит время.
        zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
        zip.putNextEntry(ZipEntry(audioFileName(document.title, source)))
        copyAudio(source, zip)
        zip.closeEntry()
        // finish, а не close: поток SAF закрывает вызывающий код.
        zip.finish()
        zip.flush()
    }

    private fun copyAudio(source: ExportAudio, output: OutputStream) {
        val input = context.contentResolver.openInputStream(source.uri.toUri())
            ?: throw IOException("Не удалось открыть исходное аудио")
        input.use { it.copyTo(output) }
    }

    private fun audioFileName(title: String, source: ExportAudio): String =
        "${safeBaseName(title)}.${source.extension ?: "audio"}"

    private suspend fun requireDocument(recordingId: String): TranscriptDocument {
        val found = document(recordingId) ?: throw IllegalStateException("Нет расшифровки для $recordingId")
        check(found.asrDraft == null) { "ASR-черновик не экспортируется" }
        return found
    }

    private fun requireAudio(recordingId: String): ExportAudio =
        audio(recordingId) ?: throw IllegalStateException("Нет исходного аудио для $recordingId")

    private fun defaultSpeakerName(number: Int): String = context.getString(R.string.speaker_default_name, number)

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    /** Сериализатор закрывает поток через bufferedWriter не всегда; защищаем ZIP от закрытия. */
    private class NonClosingStream(private val target: OutputStream) : OutputStream() {
        override fun write(b: Int) = target.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = target.write(b, off, len)
        override fun flush() = target.flush()
        override fun close() = target.flush()
    }

    companion object {
        const val SHARE_DIR = "exports"

        /** Символы, запрещённые в именах файлов SAF и FAT, заменяются на «_». */
        fun safeBaseName(title: String): String =
            title.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "transcript" }
    }
}
