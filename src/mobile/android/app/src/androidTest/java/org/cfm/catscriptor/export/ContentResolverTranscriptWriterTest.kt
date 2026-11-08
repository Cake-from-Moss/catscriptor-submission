package org.cfm.catscriptor.export

import android.app.Activity
import android.net.Uri
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.cfm.catscriptor.domain.pipeline.Transcript
import org.cfm.catscriptor.domain.pipeline.TranscriptExporter
import org.cfm.catscriptor.domain.pipeline.TranscriptFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.io.OutputStream

@RunWith(AndroidJUnit4::class)
class ContentResolverTranscriptWriterTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val resolver = context.contentResolver
    private val uri = Uri.parse("content://org.cfm.catscriptor.export.test/output")
    private val transcript = Transcript(emptyList())

    @Test fun writesUtf8OnIoAndClosesStream() = runBlocking {
        lateinit var stream: OutputStream
        val callerThread = Thread.currentThread()
        val bytes = "Привет, кот!".toByteArray(Charsets.UTF_8)
        val exporter = object : TranscriptExporter {
            override suspend fun export(transcript: Transcript, format: TranscriptFormat, output: OutputStream) {
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                assertNotSame(callerThread, Thread.currentThread())
                assertEquals(TranscriptFormat.JSON, format)
                assertSame(this@ContentResolverTranscriptWriterTest.transcript, transcript)
                stream = output
                output.write(bytes)
            }
        }
        ContentResolverTranscriptWriter(resolver, exporter).write(uri, transcript, TranscriptFormat.JSON)
        assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
        assertClosed(stream)
    }

    @Test fun closesStreamWhenSerializerFailsAndPreservesCancellation() = runBlocking {
        for (failure in listOf(IOException("Тестовая ошибка"), CancellationException("Отмена"))) {
            lateinit var stream: OutputStream
            val exporter = object : TranscriptExporter {
                override suspend fun export(transcript: Transcript, format: TranscriptFormat, output: OutputStream) {
                    stream = output
                    throw failure
                }
            }
            try {
                ContentResolverTranscriptWriter(resolver, exporter).write(uri, transcript, TranscriptFormat.TEXT)
                fail("Ожидалась ошибка")
            } catch (caught: Exception) {
                assertSame(failure, caught)
            }
            assertClosed(stream)
        }
    }

    @Test fun propagatesProviderSecurityFailure() = runBlocking {
        val exporter = object : TranscriptExporter {
            override suspend fun export(transcript: Transcript, format: TranscriptFormat, output: OutputStream) {
                fail("Запись не должна начинаться")
            }
        }
        try {
            ContentResolverTranscriptWriter(resolver, exporter).write(
                uri.buildUpon().path("denied").build(), transcript, TranscriptFormat.JSON,
            )
            fail("Ожидался запрет провайдера")
        } catch (_: SecurityException) {
            // Ошибка провайдера передаётся вызывающему коду без подмены.
        }
    }

    @Test fun documentContractsUseCorrectMimeAndTreatCancelAsNull() {
        for ((mime, name) in listOf("application/json" to "cats.json", "text/plain" to "cats.txt")) {
            val contract = ActivityResultContracts.CreateDocument(mime)
            val intent = contract.createIntent(context, name)
            assertEquals(mime, intent.type)
            assertEquals(name, intent.getStringExtra(android.content.Intent.EXTRA_TITLE))
            assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT, intent.action)
            assertNull(contract.parseResult(Activity.RESULT_CANCELED, null))
        }
    }

    @Test fun cancelledPickerClearsPendingWithoutCallback() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val state = ExportState()
            state.pending = ExportRequest(transcript, TranscriptFormat.JSON)
            state.listener = { fail("Отмена не должна вызывать обработчик результата") }
            val exporter = object : TranscriptExporter {
                override suspend fun export(transcript: Transcript, format: TranscriptFormat, output: OutputStream) {
                    fail("Отмена не должна запускать запись")
                }
            }
            state.selected(null, ContentResolverTranscriptWriter(resolver, exporter))
            assertNull(state.pending)
        }
    }

    private fun assertClosed(stream: OutputStream) {
        try {
            stream.write(1)
            fail("Поток должен быть закрыт")
        } catch (_: IOException) {
            // Закрытие проверяется через настоящий файловый дескриптор провайдера.
        }
    }
}
