package org.cfm.catscriptor.recording

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.cfm.catscriptor.audio.AndroidAudioDecoder
import org.cfm.catscriptor.domain.pipeline.AudioInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.cfm.catscriptor.MainActivity
import org.cfm.catscriptor.app.PipelineProcessingController
import org.cfm.catscriptor.app.PipelineStore
import org.cfm.catscriptor.audio.background.AudioProcessingController
import org.cfm.catscriptor.audio.background.AudioProcessingPhase
import org.cfm.catscriptor.domain.app.*
import org.junit.Assume.assumeTrue
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecordingFileProviderTest {
    @Test fun cancelRealMicrophoneReleasesAndDeletesWithoutImport() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("recordMicrophone") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            val storage = AndroidRecordingStorage(context)
            var file: File? = null
            val ownStorage = object : RecordingStorage {
                override fun availableBytes() = storage.availableBytes()
                override fun create() = storage.create().also { file = it }
                override fun uri(file: File) = storage.uri(file)
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val controller = RecordingController({ true }, { AndroidRecordingDevice(context) }, ownStorage,
                SystemClock::elapsedRealtime, { _, _ -> error("Отмена не должна импортировать аудио") }, scope)
            try {
                assertEquals(StartRecordingResult.Started, controller.start(true))
                delay(700)
                controller.pause()
                controller.cancel()
                controller.cancel()
                assertEquals(RecorderState.Idle, controller.state.value)
                assertFalse(requireNotNull(file).exists())
                assertNull(controller.stop())
            } finally { controller.cancel(); scope.cancel(); file?.delete() }
        }
    }

    @Test fun stopHandsSavedM4aToRealQueueAndService() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = PipelineStore()
        val processing = PipelineProcessingController(context, store)
        val storage = AndroidRecordingStorage(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var saved: File? = null
        // Только источник записи заменён fixture: URI, очередь, сервис и декодер настоящие.
        val device = object : RecordingDevice {
            override fun start(file: File, onError: () -> Unit) {
                saved = file
                instrumentation.context.assets.open("audio/tone-stereo-48000.m4a").use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
            }
            override fun pause() = Unit
            override fun resume() = Unit
            override fun amplitude() = 0
            override fun stop() = Unit
            override fun release() = Unit
        }
        val recorder = RecordingController({ true }, { device }, storage, SystemClock::elapsedRealtime,
            processing::importFile, scope)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            try {
                assertEquals(StartRecordingResult.Started, recorder.start(false))
                val id = requireNotNull(recorder.stop())
                val entry = withTimeout(180_000) {
                    store.entries.first { entries ->
                        (entries[id]?.processingState?.sampleCount ?: 0) > 0
                    }.getValue(id)
                }
                assertEquals(storage.uri(requireNotNull(saved)), entry.documentUri)
                val serviceState = requireNotNull(entry.processingState)
                assertTrue(serviceState.sampleCount in 15680L..16320L)
                assertEquals(StageStatus.Done::class,
                    serviceState.stages.single { it.stage == ProcessingStage.PREPARE_AUDIO }.status::class)
                assertTrue(requireNotNull(saved).exists())
            } finally {
                AudioProcessingController.cancel(context)
                withTimeout(180_000) {
                    AudioProcessingController.state.first { it.phase != AudioProcessingPhase.RUNNING }
                }
                scope.cancel()
                saved?.delete()
            }
        }
    }

    @Test fun microphonePauseResumeProducesDecodableM4a() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Явный opt-in: тест использует настоящий микрофон, не запускается в обычном CI.
        assumeTrue(InstrumentationRegistry.getArguments().getString("recordMicrophone") == "true")
        val context = instrumentation.targetContext
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE))
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}",
        ).use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        assumeTrue(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            val storage = AndroidRecordingStorage(context)
            val file = storage.create()
            val device = AndroidRecordingDevice(context)
            val recorderError = java.util.concurrent.atomic.AtomicBoolean(false)
            try {
                device.start(file) { recorderError.set(true) }
                delay(1500)
                device.pause()
                delay(300)
                device.resume()
                delay(1500)
                device.stop()
                device.release()
                assertFalse("Асинхронная ошибка микрофона", recorderError.get())
                AndroidAudioDecoder(context).decode(AudioInput(storage.uri(file))).use { pcm ->
                    assertTrue(pcm.sampleCount > 16000)
                    assertTrue(pcm.read(0, 1000).all { sample -> sample.isFinite() })
                }
            } finally { device.release(); file.delete() }
        }
    }

    @Test fun savedM4aDecodesThroughRealFileProvider() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val storage = AndroidRecordingStorage(context)
        val file = storage.create()
        try {
            instrumentation.context.assets.open("audio/tone-stereo-48000.m4a").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val uri = storage.uri(file)
            assertTrue(uri.startsWith("content://"))
            AndroidAudioDecoder(context).decode(AudioInput(uri)).use { source ->
                assertTrue(source.sampleCount in 15680L..16320L)
                assertTrue(source.read(0, 1000).all { it.isFinite() })
            }
            // Декодер удаляет свой PCM-кеш, но не исходную сохранённую запись.
            assertTrue(file.exists())
        } finally { file.delete() }
    }
}
