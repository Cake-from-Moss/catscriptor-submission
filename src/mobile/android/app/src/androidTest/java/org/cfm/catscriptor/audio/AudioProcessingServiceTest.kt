package org.cfm.catscriptor.audio

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.cfm.catscriptor.MainActivity
import org.cfm.catscriptor.audio.background.AudioProcessingController
import org.cfm.catscriptor.audio.background.AudioProcessingPhase
import org.cfm.catscriptor.audio.background.AudioProcessingState
import org.cfm.catscriptor.domain.pipeline.AudioInput
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioProcessingServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val component = "org.cfm.catscriptor/.audio.background.AudioProcessingService"
    private val lockTag = "org.cfm.catscriptor:audioPreparation"
    private var preexistingAudioDirectories = emptySet<String>()
    private val screenOffRequested: Boolean
        get() = InstrumentationRegistry.getArguments().getString("serviceScreenOff") == "true"

    @Before fun resetControllerState() {
        // Синглтон живёт в процессе приложения между прогонами instrument-раннеров:
        // без сброса чужой терминальный статус отравляет ожидания cancel-сценариев.
        AudioProcessingController.publish(AudioProcessingState())
    }

    @Before fun snapshotExistingAudioDirectories() {
        preexistingAudioDirectories = audioDirectories()
        Log.i(TAG, "Исходных каталогов аудио: ${preexistingAudioDirectories.size}")
    }

    @Test fun realWavCompletesAndReleasesResources() = withResumedActivity {
        val screenOff = screenOffRequested
        try {
            start("complete")
            awaitActiveResources()
            if (screenOff) {
                shell("input keyevent 223")
                await { isScreenOff() }
                Log.i(TAG, "Экран выключен: подтверждено dumpsys power")
            }
            await { AudioProcessingController.state.value.phase == AudioProcessingPhase.COMPLETED }
            assertEquals(1_600_000L, AudioProcessingController.state.value.sampleCount)
            assertEquals(emptyList<org.cfm.catscriptor.domain.pipeline.AsrSegment>(), AudioProcessingController.state.value.asrDraft)
            // Тишина: диаризация и коррекция завершены с пустым результатом, LLM не создавалась.
            assertEquals(emptyList<org.cfm.catscriptor.domain.pipeline.Utterance>(), AudioProcessingController.state.value.transcript)
            assertTrue(AudioProcessingController.state.value.stages.all {
                it.status is org.cfm.catscriptor.domain.app.StageStatus.Done
            })
            assertCleaned()
            if (screenOff) assertTrue("Экран снова включился до проверки завершения", isScreenOff())
            Log.i(TAG, "Подготовка WAV завершена; screenOff=$screenOff; ресурсы освобождены")
        } finally {
            if (screenOff) {
                shell("input keyevent 224")
                Log.i(TAG, "Отправлена команда пробуждения экрана")
            }
        }
    }

    @Test fun cancelLongWavReleasesResourcesAndIgnoresDuplicateStart() = withResumedActivity {
        start("long")
        awaitActiveResources()
        start("invalid")
        assertEquals(AudioProcessingPhase.RUNNING, AudioProcessingController.state.value.phase)
        AudioProcessingController.cancel(context)
        await { AudioProcessingController.state.value.phase == AudioProcessingPhase.CANCELLED }
        assertCleaned()
    }

    @Test fun invalidDocumentFailsAndReleasesResources() = withResumedActivity {
        start("invalid")
        await { AudioProcessingController.state.value.phase == AudioProcessingPhase.FAILED }
        assertEquals(0L, AudioProcessingController.state.value.sampleCount)
        assertCleaned()
    }

    private fun start(path: String) {
        AudioProcessingController.start(context, AudioInput("content://org.cfm.catscriptor.audio.service.test/$path"))
    }

    private fun awaitActiveResources() {
        await {
            AudioProcessingController.state.value.phase == AudioProcessingPhase.RUNNING &&
                shell("dumpsys activity services $component").contains("isForeground=true") &&
                activeWakeLockLines(shell("dumpsys power")).any {
                    it.contains(lockTag) && it.contains("PARTIAL_WAKE_LOCK")
                }
        }
        Log.i(TAG, "RUNNING: foreground-уведомление и PARTIAL_WAKE_LOCK подтверждены")
    }

    private fun assertCleaned() {
        var observation = "Очистка ещё не проверена"
        await(diagnostic = { observation }) {
            val foreground = shell("dumpsys activity services $component").contains("isForeground=true")
            val power = shell("dumpsys power")
            val held = activeWakeLockLines(power).any { it.contains(lockTag) }
            observation = "foreground=$foreground, heldWakeLock=$held, tagAnywhere=${power.contains(lockTag)}"
            !foreground && !held
        }
        val newDirectories = audioDirectories() - preexistingAudioDirectories
        assertTrue("Новые временные каталоги аудио не удалены: ${newDirectories.size}", newDirectories.isEmpty())
        Log.i(TAG, "Очистка подтверждена: foreground/WakeLock отсутствуют, новых каталогов нет; " +
            "phase=${AudioProcessingController.state.value.phase}; $observation")
    }

    /** История ACQ/REL и базы тегов не означают, что блокировка сейчас удерживается. */
    private fun activeWakeLockLines(power: String): List<String> {
        val lines = power.lines()
        check(lines.any { it.trim().matches(Regex("Wake Locks: size=\\d+")) }) {
            "В dumpsys power отсутствует раздел текущих Wake Locks; очистка не подтверждена"
        }
        var inside = false
        return lines.filter { line ->
            if (line.trim().matches(Regex("(?:Frozen )?Wake Locks: size=\\d+"))) {
                inside = true
                false
            } else {
                if (line.isNotBlank() && !line.first().isWhitespace()) inside = false
                inside && line.isNotBlank()
            }
        }
    }

    @Test fun wakeLockObservationIgnoresHistoryButIncludesHeldAndFrozenLocks() {
        val history = "Wake Lock Log\n  ACQ $lockTag (partial)\n  REL $lockTag\n"
        assertTrue(activeWakeLockLines("Wake Locks: size=0\n\nSuspend Blockers: size=0\n$history").isEmpty())
        for (header in listOf("Wake Locks", "Frozen Wake Locks")) {
            val dump = "Wake Locks: size=0\n\n$header: size=1\n" +
                "  PARTIAL_WAKE_LOCK '$lockTag' ACQ=-1s\n\nSuspend Blockers: size=0\n$history"
            assertEquals(1, activeWakeLockLines(dump).count { it.contains(lockTag) })
        }
    }

    private fun audioDirectories(): Set<String> = context.cacheDir.listFiles().orEmpty()
        .filter { it.isDirectory && it.name.startsWith("audio-import-") }
        .map { it.name }.toSet()

    private fun isScreenOff(): Boolean {
        val power = shell("dumpsys power")
        return Regex("mWakefulness\\s*=\\s*Asleep\\b").containsMatchIn(power) ||
            Regex("(?:mGlobalDisplayState|Display Power: state)\\s*=\\s*OFF\\b").containsMatchIn(power)
    }

    private fun withResumedActivity(block: () -> Unit) {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            // Телефон тестирующего может проснуться с keyguard (PIN): без showWhenLocked
            // activity останется STOPPED и moveToState(RESUMED) зависнет до таймаута.
            // turnScreenOn не используем: он вмешивается в тест с выключением экрана.
            scenario.onActivity { it.setShowWhenLocked(true) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            try {
                block()
            } finally {
                AudioProcessingController.cancel(context)
                await { AudioProcessingController.state.value.phase != AudioProcessingPhase.RUNNING }
            }
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun await(diagnostic: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 180_000
        while (!condition()) {
            assertTrue("Истекло время ожидания; состояние=${AudioProcessingController.state.value}; ${diagnostic()}",
                SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(100)
        }
    }

    companion object {
        private const val TAG = "AudioProcessingServiceTest"
    }
}
