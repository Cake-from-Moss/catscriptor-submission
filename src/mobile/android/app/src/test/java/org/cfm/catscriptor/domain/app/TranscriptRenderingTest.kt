package org.cfm.catscriptor.domain.app

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.cfm.catscriptor.domain.pipeline.DefaultTranscriptExporter
import org.cfm.catscriptor.domain.pipeline.TranscriptFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptRenderingTest {
    private val defaultName: (Int) -> String = { "Спикер $it" }

    private fun document(lines: List<TranscriptLine>, names: Map<String, String> = emptyMap()) = TranscriptDocument(
        recordingId = "r1",
        title = "Созвон",
        createdAtEpochMs = 0,
        durationMs = 4_000_000,
        speakers = computeSpeakers(lines, names),
        lines = lines,
    )

    private val sample = document(
        listOf(
            TranscriptLine("a", "speaker_1", 4_120, 6_380, "Добрый день! Слышите меня?"),
            TranscriptLine("b", "speaker_2", 3_725_004, 3_726_500, "Да, «всё» слышно."),
        ),
        names = mapOf("speaker_1" to "Анна"),
    )

    @Test
    fun srtIsNumberedWithCommaMillisecondsAndHours() {
        val srt = TranscriptRendering.renderSrt(sample, useSpeakerNames = false, defaultSpeakerName = defaultName)
        val expected = """
            1
            00:00:04,120 --> 00:00:06,380
            speaker_1: Добрый день! Слышите меня?

            2
            01:02:05,004 --> 01:02:06,500
            speaker_2: Да, «всё» слышно.

        """.trimIndent()
        assertEquals(expected, srt)
    }

    @Test
    fun srtTimestampPadsEveryField() {
        assertEquals("00:00:00,000", TranscriptRendering.srtTimestamp(0))
        assertEquals("00:00:00,007", TranscriptRendering.srtTimestamp(7))
        assertEquals("10:00:00,000", TranscriptRendering.srtTimestamp(36_000_000))
    }

    @Test
    fun namesFlagUsesCustomOrDefaultNameInTxtAndSrt() {
        val txt = TranscriptRendering.renderTxt(sample, useSpeakerNames = true, defaultSpeakerName = defaultName)
        assertTrue(txt.contains("[00:04 – 00:06] Анна: Добрый день!"))
        assertTrue(txt.contains("[1:02:05 – 1:02:06] Спикер 2: Да, «всё» слышно."))
        val srt = TranscriptRendering.renderSrt(sample, useSpeakerNames = true, defaultSpeakerName = defaultName)
        assertTrue(srt.contains("Анна: Добрый день!"))
        assertFalse(srt.contains("speaker_1"))
    }

    @Test
    fun namesFlagNeverChangesJson() {
        val withNames = ExportOptions(ExportFormat.JSON, useSpeakerNames = true)
        val withoutNames = ExportOptions(ExportFormat.JSON, useSpeakerNames = false)
        val a = TranscriptRendering.render(sample, withNames, defaultName)
        val b = TranscriptRendering.render(sample, withoutNames, defaultName)
        assertEquals(a, b)
        assertTrue(a.contains("\"speaker_id\": \"speaker_1\""))
        assertFalse(a.contains("Анна"))
    }

    @Test
    fun prettyJsonMatchesStrictExporterSchemaAndKeepsCyrillic() {
        val annotated = sample.copy(notes = listOf(RecordingNote("note", 1000, "Личная пометка")))
        val pretty = TranscriptRendering.renderJsonPretty(annotated)
        val output = ByteArrayOutputStream()
        runBlocking { DefaultTranscriptExporter().export(annotated.toPipelineTranscript(), TranscriptFormat.JSON, output) }
        val strict = output.toString(Charsets.UTF_8.name())
        assertEquals(JsonParser.parseString(strict), JsonParser.parseString(pretty))
        assertEquals(setOf("transcript"), JsonParser.parseString(strict).asJsonObject.keySet())
        assertFalse(strict.contains("Личная пометка"))
        assertTrue(pretty.contains("Добрый день!"))
        assertFalse(pretty.contains("\\u"))
    }

    @Test
    fun emptyDocumentRendersValidOutput() {
        val empty = document(emptyList())
        assertEquals("", TranscriptRendering.renderSrt(empty, true, defaultName))
        assertEquals("Созвон\n\n", TranscriptRendering.renderTxt(empty, true, defaultName))
        val json = JsonParser.parseString(TranscriptRendering.renderJsonPretty(empty)).asJsonObject
        assertEquals(setOf("transcript"), json.keySet())
        assertEquals(0, json.getAsJsonArray("transcript").size())
        assertEquals(
            "Созвон",
            TranscriptRendering.preview(empty, ExportOptions(ExportFormat.TXT, true), defaultName),
        )
    }

    @Test
    fun previewKeepsTenLinesAndMarksTruncation() {
        val lines = (1..20).map { TranscriptLine("l$it", "speaker_1", it * 1_000L, it * 1_000L + 500, "Реплика $it") }
        val preview = TranscriptRendering.preview(
            document(lines),
            ExportOptions(ExportFormat.JSON, useSpeakerNames = false),
            defaultName,
        ).lines()
        assertEquals(TranscriptRendering.PREVIEW_LINES + 1, preview.size)
        assertEquals("{", preview.first())
        assertEquals(TranscriptRendering.PREVIEW_ELLIPSIS, preview.last())
    }

    @Test
    fun shortTextIsNotMarkedAsTruncated() {
        assertEquals("a\nb", TranscriptRendering.firstLines("a\nb\n", maxLines = 10))
    }
}
