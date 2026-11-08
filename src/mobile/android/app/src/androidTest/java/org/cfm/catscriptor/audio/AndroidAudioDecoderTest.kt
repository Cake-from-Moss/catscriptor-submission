package org.cfm.catscriptor.audio

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.cfm.catscriptor.domain.pipeline.AudioInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAudioDecoderTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun wavDecodesAndOwnerDeletesFile() = runBlocking {
        withDocument(wav(), "audio/wav") { decoder, cache ->
            val source = decoder.decode(AudioInput("content://fixture/audio"))
            assertEquals(1600L, source.sampleCount)
            val samples = source.read(0, 1600)
            assertEquals(1600, samples.size)
            assertEquals(0.25f, samples[800], 0.01f)
            source.close()
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun invalidAndUnsupportedInputsLeaveNoFiles() = runBlocking {
        withDocument(byteArrayOf(1, 2, 3), "audio/mpeg") { decoder, cache ->
            expectFailure { decoder.decode(AudioInput("content://fixture/audio")) }
            expectFailure { decoder.decode(AudioInput("https://example.com/audio.mp3")) }
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        }
        withDocument(wav(), "text/plain") { decoder, cache ->
            expectFailure { decoder.decode(AudioInput("content://fixture/audio")) }
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun cancellationDoesNotLeakCache() = runBlocking {
        val opened = CompletableDeferred<Unit>()
        val continueWriting = java.util.concurrent.CountDownLatch(1)
        withDocument(wav(1_600_000), "audio/wav", { opened.complete(Unit) }, continueWriting) { decoder, cache ->
            val job = async(Dispatchers.Default) {
                decoder.decode(AudioInput("content://fixture/audio")).close()
            }
            opened.await()
            job.cancel()
            continueWriting.countDown()
            job.cancelAndJoin()
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun cleanupPreservesActiveSourcesAndRemovesAbandonedSession() = runBlocking {
        withDocument(wav(), "audio/wav") { decoder, cache ->
            val active = decoder.decode(AudioInput("content://fixture/audio"))
            try {
                val activeDirectory = cache.listFiles()!!.single()
                activeDirectory.setLastModified(0)
                val stale = File(cache, "audio-import-${java.util.UUID.randomUUID()}").apply { mkdir() }
                File(stale, ".lock").writeBytes(byteArrayOf())
                File(stale, "input").writeBytes(byteArrayOf(1))
                stale.setLastModified(0)
                decoder.decode(AudioInput("content://fixture/audio")).close()
                assertTrue(activeDirectory.exists())
                assertFalse(stale.exists())
                assertEquals(1600, active.read(0, 1600).size)
            } finally {
                active.close()
                active.close()
            }
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun pickerRequestsLocalAudioOnly() {
        val intent = LocalAudioDocument().createIntent(base, arrayOf("audio/*", "application/ogg"))
        assertTrue(intent.getBooleanExtra(android.content.Intent.EXTRA_LOCAL_ONLY, false))
        assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertNull(LocalAudioDocument().parseResult(android.app.Activity.RESULT_CANCELED, null))
    }

    @Test fun pipeDocumentIsCopiedBeforeWavDecode() = runBlocking {
        withDocument(wav(), "audio/wav", pipeGate = java.util.concurrent.CountDownLatch(0)) { decoder, cache ->
            decoder.decode(AudioInput("content://fixture/audio")).use { assertEquals(1600L, it.sampleCount) }
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun truncatedWavFailsAndDeletesPartialOutput() = runBlocking {
        withDocument(wav().dropLast(100).toByteArray(), "audio/wav") { decoder, cache ->
            expectFailure { decoder.decode(AudioInput("content://fixture/audio")) }
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun compressedFixturesDecodeToFiniteMono() = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        listOf("tone-stereo-44100.mp3" to "audio/mpeg", "tone-stereo-48000.m4a" to "audio/mp4",
            "tone-stereo-48000.ogg" to "application/ogg").forEach { (name, mime) ->
            val bytes = assets.open("audio/$name").use { it.readBytes() }
            withDocument(bytes, mime) { decoder, cache ->
                decoder.decode(AudioInput("content://fixture/audio")).use { source ->
                    // Контейнеры и OEM различаются наличием gapless-метаданных; двойного trim нет.
                    assertTrue("Fixture=$name, MIME=$mime, исходная длительность=1000000 мкс, " +
                        "ожидается=16000±320 отсчётов, фактически=${source.sampleCount}",
                        source.sampleCount in 15680L..16320L)
                    val samples = source.read(2000, 8000)
                    assertTrue(samples.all { it.isFinite() && it in -1f..1f })
                    val rms = kotlin.math.sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
                    // Среднее каналов даёт амплитуду 0,1875; допускаем потери сжатия.
                    assertEquals(0.1875 / kotlin.math.sqrt(2.0), rms, 0.025)
                    val crossings = samples.indices.drop(1).count { samples[it - 1] <= 0 && samples[it] > 0 }
                    assertEquals(220.0, crossings.toDouble(), 3.0)
                }
                assertTrue(cache.listFiles().orEmpty().isEmpty())
            }
        }
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block(); fail("Ожидалась ошибка импорта") }
        catch (error: AudioImportException) { assertTrue(error.message!!.isNotBlank()) }
    }

    private suspend fun withDocument(bytes: ByteArray, mime: String, opened: () -> Unit = {},
        pipeGate: java.util.concurrent.CountDownLatch? = null,
        block: suspend (AndroidAudioDecoder, File) -> Unit) {
        val root = File(base.cacheDir, "audio-test-${java.util.UUID.randomUUID()}")
        check(root.mkdir())
        val document = File(root, "fixture").apply { writeBytes(bytes) }
        val cache = File(root, "cache").apply { mkdir() }
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = mime
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                if (pipeGate != null) {
                    val ends = ParcelFileDescriptor.createPipe()
                    Thread {
                        try {
                            ParcelFileDescriptor.AutoCloseOutputStream(ends[1]).use { stream ->
                                val first = minOf(bytes.size, 65536)
                                stream.write(bytes, 0, first)
                                opened()
                                pipeGate.await()
                                stream.write(bytes, first, bytes.size - first)
                            }
                        } catch (_: java.io.IOException) {
                            // Отмена закрывает читателя; broken pipe здесь ожидаем.
                        }
                    }.start()
                    return ends[0]
                }
                opened()
                return ParcelFileDescriptor.open(document, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        val resolver = ContentResolver.wrap(provider)
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver() = resolver
            override fun getCacheDir() = cache
        }
        try { block(AndroidAudioDecoder(context), cache) }
        finally { root.deleteRecursively() }
    }

    private fun wav(count: Int = 1600): ByteArray {
        val buffer = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVEfmt ".toByteArray())
        buffer.putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000)
        buffer.putShort(2).putShort(16).put("data".toByteArray()).putInt(count * 2)
        repeat(count) { buffer.putShort(8192) }
        return buffer.array()
    }
}
