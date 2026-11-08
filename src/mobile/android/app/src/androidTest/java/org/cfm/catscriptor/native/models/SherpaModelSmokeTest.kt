package org.cfm.catscriptor.native.models

import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Properties

@RunWith(AndroidJUnit4::class)
class SherpaModelSmokeTest {
    @Test fun bundledModelReallyDecodesAndReopensWithoutNetworkPermission() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission("android.permission.INTERNET"))
        val bytes = instrumentation.context.assets.open("sherpa-example.wav").use { it.readBytes() }
        val manifest = Properties().apply {
            context.assets.open("model-manifest.properties").use { load(it) }
        }
        assertEquals(manifest.getProperty("test.wav.sha256"), MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) })
        val samples = readPcmWave(bytes)
        repeat(2) { iteration ->
            val started = SystemClock.elapsedRealtime()
            val resource = SherpaModelResource(context)
            try {
                val result = resource.diagnose(samples)
                assertTrue("Речь должна быть распознана настоящей моделью", result.text.isNotBlank())
                assertTrue(result.text.any { it in 'а'..'я' })
                assertTrue(result.tokens.isNotEmpty())
                assertEquals(result.tokens.size, result.timestampsSeconds.size)
                assertTrue(result.timestampsSeconds.all { it.isFinite() && it >= 0 && it <= samples.size / 16000f })
                assertTrue(result.timestampsSeconds.zipWithNext().all { (a, b) -> a <= b })
                Log.i("SherpaSmoke", "iteration=$iteration elapsed_ms=${SystemClock.elapsedRealtime() - started} " +
                    "native_heap_bytes=${Debug.getNativeHeapAllocatedSize()} text=${result.text}")
            } finally {
                resource.close()
                resource.close()
            }
            try {
                resource.diagnose(samples)
                fail("Закрытую модель нельзя использовать")
            } catch (_: IllegalStateException) {
                // Проверяется запрет обращения к освобождённому указателю.
            }
        }
    }

    /** Чтение только тестового RIFF PCM16 mono; production-декодер будет в отдельном issue. */
    private fun readPcmWave(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        var offset = 12
        var formatVerified = false
        while (offset + 8 <= bytes.size) {
            val name = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4)
            require(size >= 0 && offset.toLong() + 8 + size <= bytes.size)
            val start = offset + 8
            if (name == "fmt ") {
                assertTrue(size >= 16)
                assertEquals(1, buffer.getShort(start).toInt())
                assertEquals(1, buffer.getShort(start + 2).toInt())
                assertEquals(16000, buffer.getInt(start + 4))
                assertEquals(16, buffer.getShort(start + 14).toInt())
                formatVerified = true
            }
            if (name == "data") {
                check(formatVerified && size % 2 == 0)
                return FloatArray(size / 2) { buffer.getShort(start + it * 2) / 32768f }
            }
            offset = start + size + size % 2
        }
        error("В WAV отсутствует PCM")
    }
}
