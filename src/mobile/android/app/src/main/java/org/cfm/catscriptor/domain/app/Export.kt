package org.cfm.catscriptor.domain.app

enum class ExportFormat(val extension: String, val mimeType: String) {
    TXT("txt", "text/plain"),
    JSON("json", "application/json"),
    // У SRT нет MIME-типа в IANA; application/x-subrip понимают плееры и файловые менеджеры.
    SRT("srt", "application/x-subrip"),
}

/** MIME-тип архива «расшифровка + аудио». */
const val ZIP_MIME_TYPE = "application/zip"

/** Файл, который создаст [ExportGateway.saveTo]: имя для CreateDocument и его MIME-тип. */
data class SaveTarget(val fileName: String, val mimeType: String)

/**
 * [useSpeakerNames] влияет только на TXT и SRT: в JSON всегда speaker_N (AGENTS.md).
 * [includeAudio]: «Сохранить» пишет ZIP с файлом расшифровки и исходным аудио,
 * «Поделиться» отправляет оба файла одним ACTION_SEND_MULTIPLE.
 * [audioOnly]: экспортируется только исходный аудиофайл без расшифровки; [format],
 * [useSpeakerNames] и [includeAudio] тогда игнорируются.
 */
data class ExportOptions(
    val format: ExportFormat,
    val useSpeakerNames: Boolean,
    val includeAudio: Boolean = false,
    val audioOnly: Boolean = false,
)

/** Экспорт расшифровки. JSON обязан идти через DefaultTranscriptExporter, схема не меняется. */
interface ExportGateway {
    /** Первые ~10 строк выбранного формата для блока предпросмотра. */
    suspend fun preview(recordingId: String, options: ExportOptions): String

    /** [documentUri] получен из CreateDocument. Возвращает отображаемое имя сохранённого файла. */
    suspend fun saveTo(documentUri: String, recordingId: String, options: ExportOptions): String

    /** Готовит файл для системного share sheet и возвращает его content:// URI и MIME-тип. */
    suspend fun prepareShare(recordingId: String, options: ExportOptions): SharePayload

    fun suggestedFileName(title: String, format: ExportFormat): String

    /** Имя и тип документа для CreateDocument; без аудио — файл выбранного формата. */
    fun saveTarget(recordingId: String, title: String, options: ExportOptions): SaveTarget =
        SaveTarget(suggestedFileName(title, options.format), options.format.mimeType)
}

/**
 * [contentUri] — файл расшифровки; [extraContentUris] — дополнительные файлы (исходное аудио).
 * При нескольких файлах [mimeType] общий для всех (wildcard «любой тип»).
 */
data class SharePayload(
    val contentUri: String,
    val mimeType: String,
    val extraContentUris: List<String> = emptyList(),
)
