package org.cfm.catscriptor.domain.app

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.cfm.catscriptor.domain.pipeline.DefaultTranscriptExporter
import org.cfm.catscriptor.domain.pipeline.Transcript
import org.cfm.catscriptor.domain.pipeline.TranscriptFormat
import org.cfm.catscriptor.domain.pipeline.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptMappingTest {
    private fun utterance(speaker: String, start: Long, end: Long, text: String) =
        Utterance(speaker, start, end, text)

    private val transcript = Transcript(
        listOf(
            utterance("speaker_2", 0, 1_000, "Привет"),
            utterance("speaker_1", 1_000, 4_000, "Здравствуйте"),
            utterance("speaker_10", 5_000, 6_000, "Добрый вечер"),
        ),
    )

    private val metadata = TranscriptMetadata("rec", "Встреча", 42, 10_000, speakerNames = mapOf("speaker_1" to "Анна"))

    @Test
    fun sharesAreSpeechTimeFractionsOrderedBySpeakerNumber() {
        val speakers = transcript.toDocument(metadata).speakers
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_10"), speakers.map { it.id })
        assertEquals(0.6f, speakers[0].shareFraction, 1e-6f)
        assertEquals(0.2f, speakers[1].shareFraction, 1e-6f)
        assertEquals(0.2f, speakers[2].shareFraction, 1e-6f)
        assertEquals(1f, speakers.sumOf { it.shareFraction.toDouble() }.toFloat(), 1e-6f)
        assertEquals("Анна", speakers[0].displayName)
        assertNull(speakers[1].displayName)
    }

    @Test
    fun emptyTranscriptHasNoSpeakers() {
        val document = Transcript(emptyList()).toDocument(metadata)
        assertEquals(emptyList<SpeakerInfo>(), document.speakers)
        assertEquals(emptyList<TranscriptLine>(), document.lines)
    }

    @Test
    fun lineIdsAreStableAcrossMappingsAndUnique() {
        val first = transcript.toDocument(metadata).lines.map { it.id }
        val second = transcript.toDocument(metadata.copy(title = "Другое")).lines.map { it.id }
        assertEquals(first, second)
        assertEquals(first.size, first.toSet().size)
    }

    @Test
    fun recomputeKeepsNamesAndSpeakersWithoutLines() {
        val document = transcript.toDocument(metadata)
        val moved = document.copy(lines = document.lines.map { it.copy(speakerId = "speaker_1") })
            .withRecomputedSpeakers()
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_10"), moved.speakers.map { it.id })
        assertEquals(1f, moved.speakers[0].shareFraction, 1e-6f)
        assertEquals(0f, moved.speakers[1].shareFraction, 0f)
        assertEquals("Анна", moved.speakers[0].displayName)
    }

    @Test
    fun roundTripPreservesTextAndAbsoluteLongIntervals() {
        val start = Int.MAX_VALUE.toLong() + 1000
        val original = Transcript(listOf(
            utterance("speaker_1", start, start + 500, ""),
            utterance("speaker_2", start + 500, start + 1000, "  Кот\nмяу!  "),
        ))
        val document = original.toDocument(metadata)
        assertEquals(original, document.toPipelineTranscript())
    }

    @Test
    fun documentRoundTripsThroughStrictExporter() {
        val document = transcript.toDocument(metadata).let { doc ->
            doc.copy(lines = doc.lines.map { if (it.speakerId == "speaker_2") it.copy(text = " ") else it })
        }
        val output = ByteArrayOutputStream()
        runBlocking { DefaultTranscriptExporter().export(document.toPipelineTranscript(), TranscriptFormat.JSON, output) }
        val items = JsonParser.parseString(output.toString(Charsets.UTF_8.name())).asJsonObject.getAsJsonArray("transcript")
        assertEquals(3, items.size())
        val first = items[0].asJsonObject
        assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), first.keySet())
        assertEquals("speaker_2", first["speaker_id"].asString)
        assertEquals(0L, first["start_ms"].asLong)
        assertEquals(1_000L, first["end_ms"].asLong)
        // Очищенный пользователем текст экспортируется как есть, а не заменяется заглушкой.
        assertEquals(" ", first["text"].asString)
        assertEquals("Здравствуйте", items[1].asJsonObject["text"].asString)
    }
}
