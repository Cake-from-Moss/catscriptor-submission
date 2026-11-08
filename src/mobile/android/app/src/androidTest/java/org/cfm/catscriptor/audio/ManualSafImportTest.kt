package org.cfm.catscriptor.audio

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.cfm.catscriptor.MainActivity
import org.cfm.catscriptor.domain.pipeline.AudioInput
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Ручной SAF-тест: без аргумента запуска пропускается, автоматизацию DocumentsUI не подменяет. */
@RunWith(AndroidJUnit4::class)
class ManualSafImportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    @Test
    fun selectDocumentAndDecode() {
        assumeTrue(arguments.getString("runManualSaf") == "true")
        withPicker(false) { result, _ ->
            val input = result.get(180, TimeUnit.SECONDS).getOrThrow()
            val uri = Uri.parse(input.documentId)
            assertEquals("content", uri.scheme)
            val mime = base.contentResolver.getType(uri)
            assertNotNull("Document must expose a MIME type", mime)
            assertTrue("Unexpected audio MIME: $mime", mime!!.startsWith("audio/") ||
                mime in setOf("application/ogg", "application/octet-stream"))
            // Читаем тем же реальным resolver: проверяем выданный SAF grant, не выдаём его сами.
            base.contentResolver.openInputStream(uri).use { stream ->
                assertNotNull("SAF document must be readable", stream)
                assertTrue("SAF document must not be empty", stream!!.read() >= 0)
            }
            val before = sessions()
            try {
                runBlocking(Dispatchers.Default) {
                    val started = SystemClock.elapsedRealtime()
                    AndroidAudioDecoder(base).decode(input).use { source ->
                        val elapsed = SystemClock.elapsedRealtime() - started
                        assertTrue(source.sampleCount > 0)
                        for (offset in listOf(0L, source.sampleCount / 2, (source.sampleCount - 4096).coerceAtLeast(0))) {
                            val samples = source.read(offset, 4096)
                            assertTrue(samples.isNotEmpty())
                            assertTrue(samples.size <= 4096)
                            assertTrue(samples.all { it.isFinite() && it in -1f..1f })
                        }
                        arguments.getString("manualExpectedSeconds")?.let { expected ->
                            val seconds = expected.toDouble()
                            require(seconds.isFinite() && seconds > 0)
                            assertEquals(seconds, source.sampleCount / 16000.0, 0.020)
                        }
                        Log.i(TAG, "authority=${uri.authority}, mime=$mime, sampleCount=${source.sampleCount}, decodeElapsedMs=$elapsed")
                    }
                }
                assertTrue("Decoder leaked a new cache session", (sessions() - before).isEmpty())
            } finally {
                // Никогда не удаляем сессии, существовавшие до этого декодирования.
                (sessions() - before).forEach { it.deleteRecursively() }
            }
        }
    }

    @Test
    fun cancelDocumentSelection() {
        assumeTrue(arguments.getString("manualSafCancel") == "true")
        withPicker(true) { result, returned ->
            assertTrue("Cancel the picker within 180 seconds", returned.await(180, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            // Даём отложенной доставке результата завершиться; отмена callback не вызывает.
            assertFalse("Cancellation must not invoke the import callback", result.isDone)
        }
    }

    private fun withPicker(
        cancel: Boolean,
        block: (CompletableFuture<Result<AudioInput>>, CountDownLatch) -> Unit,
    ) {
        val application = base.applicationContext as Application
        val result = CompletableFuture<Result<AudioInput>>()
        val returned = CountDownLatch(1)
        val resumes = AtomicInteger()
        var controller: AudioImportController? = null
        var activity: Activity? = null
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(created: Activity, state: Bundle?) {
                if (created is MainActivity) {
                    activity = created
                    // Application callback вызывается до STARTED: production-контроллер требует это.
                    controller = AudioImportController(created, { result.complete(it) }, "manual-saf-import")
                }
            }
            override fun onActivityResumed(resumed: Activity) {
                if (resumed === activity && resumes.incrementAndGet() >= 2) returned.countDown()
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(callbacks) }
        try {
            instrumentation.startActivitySync(Intent(base, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            Log.i(TAG, if (cancel) "MANUAL: cancel DocumentsUI using Back within 180 seconds."
                else "MANUAL: select Download/catscriptor-long-30min.mp3 in DocumentsUI within 180 seconds.")
            instrumentation.runOnMainSync { checkNotNull(controller).launch() }
            block(result, returned)
        } finally {
            instrumentation.runOnMainSync {
                application.unregisterActivityLifecycleCallbacks(callbacks)
                activity?.finish()
            }
        }
    }

    private fun sessions(): Set<File> = base.cacheDir.listFiles().orEmpty()
        .filter { it.isDirectory && it.name.matches(Regex("audio-import-[0-9a-f-]{36}")) }.toSet()

    private companion object {
        const val TAG = "ManualSafImportTest"
    }
}
