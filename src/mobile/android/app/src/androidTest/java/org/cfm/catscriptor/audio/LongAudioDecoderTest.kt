package org.cfm.catscriptor.audio

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.cfm.catscriptor.domain.pipeline.AudioInput
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Только по -e runLongAudio true. WAV: -e longAudioSeconds 1800 (1800..7200).
 * MP3: -e longAudioPath /путь/доступный/target.mp3 -e longAudioExpectedSeconds 1800.
 * Внешний MP3 должен содержать тот же непрерывный тон 440 Гц: каналы 0,25 и 0,125,
 * 44100 Гц, stereo; длительность >=1800 с, gapless-метаданные для допуска 20 мс.
 * Внешний файл никогда не удаляется. Для каждого теста предел времени — 30 минут.
 */
@RunWith(AndroidJUnit4::class)
class LongAudioDecoderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val base get() = instrumentation.targetContext

    @Test(timeout = 1_800_000L)
    fun generatedThirtyMinuteWav() = runBlocking {
        optIn()
        val seconds = args.getString("longAudioSeconds", "1800")!!.toInt()
        require(seconds in 1800..7200)
        withRoot { root ->
            val input = File(root, "generated.wav")
            writeWav(input, seconds)
            verify(input, "audio/wav", seconds.toDouble(), true, root)
        }
    }

    @Test(timeout = 1_800_000L)
    fun suppliedThirtyMinuteMp3() = runBlocking {
        optIn()
        val path = args.getString("longAudioPath")
        assumeTrue("Нужен longAudioPath для внешнего MP3", !path.isNullOrBlank())
        val seconds = requireNotNull(args.getString("longAudioExpectedSeconds")) {
            "Укажите longAudioExpectedSeconds для известной исходной длительности"
        }.toDouble()
        require(seconds.isFinite() && seconds >= 1800)
        val input = File(path!!)
        require(input.isFile && input.canRead()) { "Файл недоступен target: $path" }
        withRoot { verify(input, "audio/mpeg", seconds, false, it) }
    }

    private fun optIn() = assumeTrue("Долгий тест отключён", args.getString("runLongAudio") == "true")

    private suspend fun withRoot(block: suspend (File) -> Unit) {
        val root = File(base.cacheDir, "long-audio-test-${UUID.randomUUID()}")
        check(root.mkdir())
        try { block(root) } finally { root.deleteRecursively() }
    }

    private suspend fun verify(input: File, mime: String, seconds: Double, exact: Boolean, root: File) {
        val cache = File(root, "decoder-cache").apply { check(mkdir()) }
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = mime
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
                ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
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
        val before = heapUsed()
        val peak = AtomicLong(before)
        val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = thread(name = "long-audio-heap-sampler", isDaemon = true) {
            while (sampling.get()) {
                peak.accumulateAndGet(heapUsed(), ::maxOf)
                try { Thread.sleep(25) } catch (_: InterruptedException) { break }
            }
        }
        val started = SystemClock.elapsedRealtime()
        val source = try {
            AndroidAudioDecoder(context).decode(AudioInput("content://long-fixture/audio"))
        } finally {
            sampling.set(false)
            sampler.interrupt()
            sampler.join()
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        val after = heapUsed()
        source.use {
            val cacheBytes = cache.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            Log.i("LongAudioDecoderTest", "inputBytes=${input.length()} outputSamples=${it.sampleCount} " +
                "actualSeconds=${it.sampleCount / 16000.0} expectedSeconds=$seconds " +
                "decodeElapsedMs=$elapsed RTF=${elapsed / 1000.0 / seconds} " +
                "javaHeapBefore=$before javaHeapAfter=$after sampledJavaHeapMax=${peak.get()} " +
                "samplePeriodMs=25 cacheBytes=$cacheBytes")
            // Выборочный максимум Java heap не доказывает жёсткую границу, native heap не измеряется.
            val expected = (seconds * 16000).toLong()
            assertTrue("Ожидалось $expected, фактически ${it.sampleCount}",
                kotlin.math.abs(it.sampleCount - expected) <= if (exact) 0L else 320L)
            assertEquals(it.sampleCount * 4, cache.walkTopDown()
                .filter { file -> file.isFile && file.name == "output.f32" }.sumOf { file -> file.length() })
            for (offset in listOf(16000L, it.sampleCount / 2, it.sampleCount - 32000)) {
                val samples = it.read(offset, 16000)
                assertEquals(16000, samples.size)
                checkTone(samples)
            }
            var position = 0L
            while (position < it.sampleCount) {
                val count = minOf(65536L, it.sampleCount - position).toInt()
                val samples = it.read(position, count)
                assertTrue("Пустой или слишком большой блок около $position", samples.size in 1..count)
                assertTrue("Некорректный PCM около $position", samples.all { value -> value.isFinite() && value in -1f..1f })
                position += samples.size
            }
            assertEquals(it.sampleCount, position)
        }
        assertTrue("close должен удалить PCM и каталог сессии", cache.listFiles().orEmpty().isEmpty())
    }

    private fun checkTone(samples: FloatArray) {
        assertTrue(samples.all { it.isFinite() && it in -1f..1f })
        val rms = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
        assertEquals(0.1875 / sqrt(2.0), rms, 0.025)
        var crossings = 0
        for (i in 1 until samples.size) if (samples[i - 1] <= 0 && samples[i] > 0) crossings++
        assertEquals(440.0, crossings.toDouble(), 3.0)
    }

    private fun heapUsed(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    private fun writeWav(file: File, seconds: Int) {
        val frames = seconds.toLong() * 44100
        val dataBytes = frames * 4
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVEfmt ".toByteArray())
        header.putInt(16).putShort(1).putShort(2).putInt(44100).putInt(176400)
        header.putShort(4).putShort(16).put("data".toByteArray()).putInt(dataBytes.toInt())
        // Одна секунда периодического тона переиспользуется без роста памяти с длительностью.
        val block = ByteBuffer.allocate(44100 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(44100) { frame ->
            val tone = sin(2 * PI * 440 * frame / 44100)
            block.putShort((tone * 8192).toInt().toShort())
            block.putShort((tone * 4096).toInt().toShort())
        }
        file.outputStream().buffered(65536).use { output ->
            output.write(header.array())
            repeat(seconds) { output.write(block.array()) }
        }
        assertEquals(44 + dataBytes, file.length())
    }
}
