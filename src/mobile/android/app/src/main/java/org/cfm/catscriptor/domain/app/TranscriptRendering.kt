package org.cfm.catscriptor.domain.app

import com.google.gson.stream.JsonWriter
import java.io.StringWriter
import java.util.Locale

/**
 * Текстовые представления расшифровки для TXT, SRT и предпросмотра JSON. Функции чистые:
 * ни файлов, ни Android. Строгий JSON-файл сюда не входит, его пишет DefaultTranscriptExporter.
 */
object TranscriptRendering {
    /** Сколько строк показывает блок предпросмотра на листе экспорта. */
    const val PREVIEW_LINES = 10

    /** Последняя строка обрезанного предпросмотра. */
    const val PREVIEW_ELLIPSIS = "…"

    /**
     * Подпись спикера в TXT и SRT. С [useSpeakerNames] берётся имя пользователя или
     * [defaultSpeakerName] (например, «Спикер 2»); без него — исходный `speaker_N`.
     */
    fun speakerLabel(
        speakerId: String,
        speakers: List<SpeakerInfo>,
        useSpeakerNames: Boolean,
        defaultSpeakerName: (Int) -> String,
    ): String {
        if (!useSpeakerNames) return speakerId
        val custom = speakers.firstOrNull { it.id == speakerId }?.displayName?.takeIf { it.isNotBlank() }
        return custom ?: speakerNumber(speakerId)?.let(defaultSpeakerName) ?: speakerId
    }

    /** Строка на реплику: `[мм:сс – мм:сс] Спикер: текст`, сверху название записи. */
    fun renderTxt(
        document: TranscriptDocument,
        useSpeakerNames: Boolean,
        defaultSpeakerName: (Int) -> String,
    ): String = buildString {
        require(document.asrDraft == null) { "ASR draft cannot be exported" }
        append(document.title).append('\n').append('\n')
        for (line in document.lines.sortedBy { it.startMs }) {
            val speaker = speakerLabel(line.speakerId, document.speakers, useSpeakerNames, defaultSpeakerName)
            append('[').append(clock(line.startMs)).append(" – ").append(clock(line.endMs)).append("] ")
            append(speaker).append(": ").append(line.text).append('\n')
        }
    }

    /** SubRip: нумерация с 1, `ЧЧ:ММ:СС,ммм --> ЧЧ:ММ:СС,ммм`, пустая строка между блоками. */
    fun renderSrt(
        document: TranscriptDocument,
        useSpeakerNames: Boolean,
        defaultSpeakerName: (Int) -> String,
    ): String = buildString {
        require(document.asrDraft == null) { "ASR draft cannot be exported" }
        document.lines.sortedBy { it.startMs }.forEachIndexed { index, line ->
            val speaker = speakerLabel(line.speakerId, document.speakers, useSpeakerNames, defaultSpeakerName)
            if (index > 0) append('\n')
            append(index + 1).append('\n')
            append(srtTimestamp(line.startMs)).append(" --> ").append(srtTimestamp(line.endMs)).append('\n')
            append(speaker).append(": ").append(line.text).append('\n')
        }
    }

    /**
     * Та же схема, что у DefaultTranscriptExporter, но с отступами: компактная строка в блоке
     * предпросмотра нечитаема. Имена спикеров сюда не передаются — в JSON всегда `speaker_N`.
     */
    fun renderJsonPretty(document: TranscriptDocument): String {
        require(document.asrDraft == null) { "ASR draft cannot be exported" }
        val out = StringWriter()
        JsonWriter(out).use { json ->
            json.setIndent("  ")
            json.beginObject().name("transcript").beginArray()
            for (line in document.lines.sortedBy { it.startMs }) {
                json.beginObject()
                    .name("speaker_id").value(line.speakerId)
                    .name("start_ms").value(line.startMs)
                    .name("end_ms").value(line.endMs)
                    .name("text").value(line.text)
                    .endObject()
            }
            json.endArray().endObject()
        }
        return out.toString()
    }

    /** Полный текст выбранного формата; для JSON — читаемая версия той же схемы. */
    fun render(
        document: TranscriptDocument,
        options: ExportOptions,
        defaultSpeakerName: (Int) -> String,
    ): String = when (options.format) {
        ExportFormat.TXT -> renderTxt(document, options.useSpeakerNames, defaultSpeakerName)
        ExportFormat.SRT -> renderSrt(document, options.useSpeakerNames, defaultSpeakerName)
        ExportFormat.JSON -> renderJsonPretty(document)
    }

    /** Первые [maxLines] строк формата; если текст длиннее, последней строкой идёт «…». */
    fun preview(
        document: TranscriptDocument,
        options: ExportOptions,
        defaultSpeakerName: (Int) -> String,
        maxLines: Int = PREVIEW_LINES,
    ): String = firstLines(render(document, options, defaultSpeakerName), maxLines)

    fun firstLines(text: String, maxLines: Int): String {
        val lines = text.trimEnd('\n').lines()
        return if (lines.size <= maxLines) {
            lines.joinToString("\n")
        } else {
            (lines.take(maxLines) + PREVIEW_ELLIPSIS).joinToString("\n")
        }
    }

    fun srtTimestamp(ms: Long): String {
        val value = ms.coerceAtLeast(0)
        return String.format(
            Locale.ROOT, "%02d:%02d:%02d,%03d",
            value / 3_600_000, value / 60_000 % 60, value / 1000 % 60, value % 1000,
        )
    }

    /** `мм:сс`, для записей от часа `ч:мм:сс`. */
    private fun clock(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val hours = total / 3600
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, total / 60 % 60, total % 60)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60)
        }
    }
}
