package org.cfm.catscriptor.domain.pipeline

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DefaultTranscriptExporterTest {
    private val exporter = DefaultTranscriptExporter()

    private fun utterance(start: Long = 1250, end: Long = 4380, text: String = "Добрый день!", speaker: String = "speaker_1") =
        Utterance(speaker, start, end, text)

    private fun export(transcript: Transcript, format: TranscriptFormat): ByteArray {
        val output = ByteArrayOutputStream()
        runBlocking { exporter.export(transcript, format, output) }
        return output.toByteArray()
    }

    @Test
    fun strictSchemaAndUtf8Fixture() {
        val bytes = export(Transcript(listOf(utterance())), TranscriptFormat.JSON)
        val fixture = """{"transcript":[{"speaker_id":"speaker_1","start_ms":1250,"end_ms":4380,"text":"Добрый день!"}]}"""
        assertArrayEquals(fixture.toByteArray(Charsets.UTF_8), bytes)
        val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        assertEquals(setOf("transcript"), root.keySet())
        val item = root.getAsJsonArray("transcript")[0].asJsonObject
        assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), item.keySet())
        assertTrue(item["speaker_id"].asJsonPrimitive.isString)
        assertTrue(item["text"].asJsonPrimitive.isString)
        assertEquals("Добрый день!", item["text"].asString)
        assertTrue(item["start_ms"].asJsonPrimitive.isNumber)
        assertTrue(item["end_ms"].asJsonPrimitive.isNumber)
    }

    @Test
    fun escapesEveryControlCharacterQuotesBackslashAndCyrillic() {
        val text = "Кошка \"мяу\" \\ " + (0..31).map(Int::toChar).joinToString("") + "\u2028\u2029"
        val json = export(Transcript(listOf(utterance(text = text))), TranscriptFormat.JSON).toString(Charsets.UTF_8)
        assertFalse(json.any { it.code < 32 })
        assertEquals(text, JsonParser.parseString(json).asJsonObject.getAsJsonArray("transcript")[0].asJsonObject["text"].asString)
    }

    @Test
    fun emptyTranscriptAndEmptyText() {
        assertEquals("{\"transcript\":[]}", export(Transcript(emptyList()), TranscriptFormat.JSON).toString(Charsets.UTF_8))
        assertArrayEquals(byteArrayOf(), export(Transcript(emptyList()), TranscriptFormat.TEXT))
        val json = export(Transcript(listOf(utterance(text = ""))), TranscriptFormat.JSON).toString(Charsets.UTF_8)
        assertEquals("", JsonParser.parseString(json).asJsonObject.getAsJsonArray("transcript")[0].asJsonObject["text"].asString)
    }

    @Test
    fun preservesEqualStartsOverlapsSpeakersAndLongPrecision() {
        val items = listOf(
            utterance(), utterance(end = 5000, speaker = "speaker_2"),
            utterance(2000, 6000), utterance(Int.MAX_VALUE.toLong() + 1, Int.MAX_VALUE.toLong() + 2),
            utterance(9_007_199_254_740_993, Long.MAX_VALUE),
        )
        val json = export(Transcript(items), TranscriptFormat.JSON).toString(Charsets.UTF_8)
        val array = JsonParser.parseString(json).asJsonObject.getAsJsonArray("transcript")
        assertEquals(items.size, array.size())
        items.forEachIndexed { index, expected ->
            val actual = array[index].asJsonObject
            assertEquals(expected.speakerId, actual["speaker_id"].asString)
            assertEquals(expected.startMs.toString(), actual["start_ms"].asString)
            assertEquals(expected.endMs.toString(), actual["end_ms"].asString)
            assertEquals(expected.text, actual["text"].asString)
        }
    }

    @Test
    fun textHasUnboundedHoursAndLocaleIndependentUtf8() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val items = listOf(utterance(), utterance(3600001, 3601002), utterance(360000000, Long.MAX_VALUE))
            val expected = "[00:00:01.250 - 00:00:04.380] speaker_1: Добрый день!\n" +
                "[01:00:00.001 - 01:00:01.002] speaker_1: Добрый день!\n" +
                "[100:00:00.000 - 2562047788015:12:55.807] speaker_1: Добрый день!\n"
            assertArrayEquals(expected.toByteArray(Charsets.UTF_8), export(Transcript(items), TranscriptFormat.TEXT))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun flushesButNeverClosesCallerStream() {
        for (format in TranscriptFormat.entries) {
            val output = TrackingStream()
            runBlocking { exporter.export(Transcript(listOf(utterance())), format, output) }
            assertTrue(output.flushed)
            assertFalse(output.closed)
            output.write(33)
            assertTrue(output.size() > 1)
        }
    }

    @Test
    fun propagatesWriteAndFlushErrorsWithoutClosingStream() {
        for (format in TranscriptFormat.entries) {
            for (failOnFlush in listOf(false, true)) {
                val failure = IOException("ошибка вывода")
                var closed = false
                val output = object : OutputStream() {
                    override fun write(value: Int) { if (!failOnFlush) throw failure }
                    override fun flush() { if (failOnFlush) throw failure }
                    override fun close() { closed = true }
                }
                val thrown = assertThrows(IOException::class.java) {
                    runBlocking { exporter.export(Transcript(listOf(utterance(text = "я".repeat(20000)))), format, output) }
                }
                assertEquals(failure.javaClass, thrown.javaClass)
                assertEquals(failure.message, thrown.message)
                assertFalse(closed)
            }
        }
    }

    @Test
    fun writesAndFlushesOutsideCallerThread() = runBlocking {
        val caller = Thread.currentThread()
        for (format in TranscriptFormat.entries) {
            val output = object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    assertNotSame(caller, Thread.currentThread())
                    super.write(bytes, offset, length)
                }
                override fun flush() { assertNotSame(caller, Thread.currentThread()) }
            }
            exporter.export(Transcript(listOf(utterance())), format, output)
            assertTrue(output.size() > 0)
        }
    }

    private class TrackingStream : ByteArrayOutputStream() {
        var closed = false
        var flushed = false
        override fun flush() { flushed = true; super.flush() }
        override fun close() { closed = true; super.close() }
    }
}
