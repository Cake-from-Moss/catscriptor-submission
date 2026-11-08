package org.cfm.catscriptor.native.models

import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.cfm.catscriptor.domain.pipeline.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Реальная проверка LLM-коррекции на ARM64-устройстве: настоящий JNI + GGUF из APK,
 * без сети. Выполняется долго (загрузка ~1 ГБ модели), поэтому это smoke, а не regression.
 */
@RunWith(AndroidJUnit4::class)
class LlamaCorrectionSmokeTest {

    @Test fun bundledLlmCorrectsRussianUtterancesFullyOffline() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission("android.permission.INTERNET"),
        )

        val corrector = LlamaTextCorrector.create(context)
        val timings = mutableListOf<Pair<String, Long>>()
        try {
            val loadStarted = SystemClock.elapsedRealtime()
            // Первая реплика загружает модель: время включает копирование/подготовку GGUF.
            val first = timed("загрузка+первая реплика", loadStarted) {
                corrector.correct(Utterance("speaker_1", 0, 1000, "привет как дела"))
            }
            timings += "first_total_ms" to SystemClock.elapsedRealtime() - loadStarted
            assertTrue("Первая реплика не должна быть пустой", first.isNotBlank())
            assertTrue("Ответ должен остаться русским текстом", first.any { it in 'а'..'я' || it in 'А'..'Я' })

            // Пунктуация/регистр: почти наверняка меняются, но guard не даёт переписать смысл.
            val second = timed("пунктуация", SystemClock.elapsedRealtime()) {
                corrector.correct(Utterance("speaker_1", 1000, 2500, "ну давай встретимся завтра в пятницу"))
            }
            assertTrue(second.any { it in 'а'..'я' })
            assertTrue(
                "Слова исходника должны сохраниться (guard): $second",
                listOf("встретимся", "завтра", "пятницу").all { it.lowercase() in second.lowercase() },
            )

            // Цифры неприкосновенны: даже если модель их изменит, guard вернёт исходник.
            val digits = corrector.correct(Utterance("speaker_2", 3000, 4500, "встреча в 15 30"))
            assertTrue("15 должно сохраниться: $digits", digits.contains("15"))
            assertTrue("30 должно сохраниться: $digits", digits.contains("30"))

            // Отрицания неприкосновенны.
            val negation = corrector.correct(Utterance("speaker_2", 5000, 6500, "я не приду завтра"))
            assertTrue("Отрицание должно сохраниться: $negation", negation.lowercase().contains("не приду"))

            // UTF-8/смешанная лексика не ломает JNI-путь.
            val mixed = corrector.correct(Utterance("speaker_1", 7000, 9000, "зайди на сайт rambler ru"))
            assertTrue(mixed.isNotBlank())

            // Пустой текст — генерация не вызывается, ответ пустой.
            assertEquals("  ", corrector.correct(Utterance("speaker_1", 9500, 9600, "  ")))
        } finally {
            corrector.close()
            corrector.close() // двойное закрытие безопасно
            timings.forEach { Log.i(TAG, "${it.first}=${it.second}ms") }
        }

        // После close использовать корректор нельзя.
        try {
            corrector.correct(Utterance("speaker_1", 0, 1, "привет"))
            fail("Закрытый корректор должен запрещать вызовы")
        } catch (_: IllegalStateException) {
            // ожидаемый запрет use-after-close
        }

        // Повторная загрузка после закрытия: новая сессия, модель снова из APK-хранилища.
        val reopened = LlamaTextCorrector.create(context)
        try {
            val text = reopened.correct(Utterance("speaker_1", 0, 800, "привет как дела"))
            assertTrue(text.isNotBlank())
        } finally {
            reopened.close()
        }

        // Отмена: корректор обязан отдать CancellationException, а не зависнуть в нативном вызове.
        val cancelled = LlamaTextCorrector.create(context)
        try {
            val job = launch(Dispatchers.Default) {
                repeat(50) { cancelled.correct(Utterance("speaker_1", 0, 100, "да ну короче")) }
            }
            job.cancel()
            job.join()
        } finally {
            cancelled.close()
        }

        Log.i(TAG, "native_heap_mb=${Debug.getNativeHeapAllocatedSize() / (1024 * 1024)}")
        Unit
    }

    private suspend fun timed(label: String, since: Long, block: suspend () -> String): String {
        val text = block()
        Log.i(TAG, "$label: ${SystemClock.elapsedRealtime() - since}ms -> $text")
        return text
    }

    private companion object {
        const val TAG = "LlamaSmoke"
    }
}
