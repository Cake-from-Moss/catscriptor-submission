package org.cfm.catscriptor.domain.pipeline

import com.google.gson.stream.JsonWriter
import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Потоковый экспорт без изменения порядка реплик и без владения выходным потоком. */
class DefaultTranscriptExporter : TranscriptExporter {
    override suspend fun export(
        transcript: Transcript,
        format: TranscriptFormat,
        output: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val writer = output.bufferedWriter(Charsets.UTF_8)
        when (format) {
            TranscriptFormat.JSON -> {
                val json = JsonWriter(writer)
                json.beginObject().name("transcript").beginArray()
                for (utterance in transcript.transcript) {
                    json.beginObject()
                        .name("speaker_id").value(utterance.speakerId)
                        .name("start_ms").value(utterance.startMs)
                        .name("end_ms").value(utterance.endMs)
                        .name("text").value(utterance.text)
                        .endObject()
                }
                json.endArray().endObject()
                json.flush()
            }
            TranscriptFormat.TEXT -> {
                for (utterance in transcript.transcript) {
                    writer.append("[${timestamp(utterance.startMs)} - ${timestamp(utterance.endMs)}] ")
                    writer.append(utterance.speakerId).append(": ").append(utterance.text).append('\n')
                }
                writer.flush()
            }
        }
    }

    private fun timestamp(milliseconds: Long): String = String.format(
        Locale.ROOT, "%02d:%02d:%02d.%03d",
        milliseconds / 3_600_000, milliseconds / 60_000 % 60,
        milliseconds / 1000 % 60, milliseconds % 1000,
    )
}
