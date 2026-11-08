package org.cfm.catscriptor.native.models

import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.cfm.catscriptor.audio.AndroidAudioDecoder
import org.cfm.catscriptor.domain.pipeline.AudioInput
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ручная проверка диаризации на настоящей записи команды. Записи не коммитятся: файл кладётся
 * в `files/recordings/` приложения, имя и ожидаемое число спикеров передаются аргументами:
 *
 * ```
 * adb push voices.m4a /data/local/tmp/ && adb shell "cat /data/local/tmp/voices.m4a | \
 *   run-as org.cfm.catscriptor sh -c 'mkdir -p files/recordings && cat > files/recordings/voices.m4a'"
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=org.cfm.catscriptor.native.models.DiarizationRecordingCheck \
 *   -Pandroid.testInstrumentationRunnerArguments.diarizationFile=voices.m4a \
 *   -Pandroid.testInstrumentationRunnerArguments.expectedSpeakers=2
 * adb logcat -s DiarizationCheck
 * ```
 * Без аргумента `diarizationFile` тест пропускается.
 */
@RunWith(AndroidJUnit4::class)
class DiarizationRecordingCheck {
    @Test fun recordingHasExpectedNumberOfSpeakers() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        val name = arguments.getString("diarizationFile")
        assumeTrue("Не задан diarizationFile", name != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "recordings/$name")
        assumeTrue("Нет файла $file", file.isFile)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.recordings", file).toString()

        AndroidAudioDecoder(context).decode(AudioInput(uri)).use { pcm ->
            val speech = SherpaVoiceActivityDetector(context).use { it.detect(pcm) }
            val started = System.nanoTime()
            val speakers = SherpaDiarizationBackend(context).use { it.diarize(pcm, speech) }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val audioMs = pcm.sampleCount / 16
            Log.i(TAG, "file=$name audio_ms=$audioMs diarization_ms=$elapsedMs rtf=${elapsedMs.toDouble() / audioMs}")
            speakers.forEach { Log.i(TAG, "${it.time.startMs}-${it.time.endMs} ${it.speakerId}") }
            val found = speakers.map { it.speakerId }.distinct().size
            Log.i(TAG, "speakers=$found")
            arguments.getString("expectedSpeakers")?.toInt()?.let { assertEquals(it, found) }
        }
    }

    private companion object {
        const val TAG = "DiarizationCheck"
    }
}
