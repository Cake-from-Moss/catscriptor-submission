package org.cfm.catscriptor.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.media.AudioRecord
import android.media.AudioFormat
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.cfm.catscriptor.domain.app.ProcessingController
import org.cfm.catscriptor.domain.app.RecorderController

/** Контроллер живёт столько же, сколько граф приложения; микрофон защищён foreground service. */
fun androidRecordingController(
    context: Context,
    processing: ProcessingController,
    saveNotes: suspend (String, List<org.cfm.catscriptor.domain.app.RecordingNote>) -> Unit = { _, _ -> },
): RecorderController {
    val app = context.applicationContext
    return RecordingController(
        available = {
            app.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) &&
                ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            // Публичный API не даёт универсально проверить системный mute микрофона:
            // ошибки старта/рекордера обрабатываются, нулевая амплитуда не считается VAD.
        },
        factory = { AndroidRecordingDevice(app) },
        storage = AndroidRecordingStorage(app),
        clock = SystemClock::elapsedRealtime,
        importFile = processing::importFile,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        supported = { app.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) },
        saveNotes = saveNotes,
    )
}

internal class AndroidRecordingStorage(private val context: Context) : RecordingStorage {
    private val directory get() = File(context.filesDir, "recordings")
    override fun availableBytes() = context.filesDir.usableSpace
    override fun create(): File {
        check(directory.isDirectory || directory.mkdirs())
        return File.createTempFile("recording-", ".wav", directory)
    }
    override fun uri(file: File): String = FileProvider.getUriForFile(
        context, "${context.packageName}.recordings", file,
    ).toString()
}

internal class AndroidRecordingDevice(private val context: Context) : RecordingDevice {
    private var recorder: AudioRecord? = null
    private var writer: WavPcmWriter? = null
    private var reader: Thread? = null
    private var live: LiveSpeechPreview? = null
    private val frameLock = Any()
    private val previewLock = Any()
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var captureGeneration = 0L
    @Volatile private var peak = 0
    @Volatile private var savedSamples = 0L
    @Volatile private var snapshot = PreviewState()
    @Volatile private var failure: Throwable? = null
    override val recordedSamples get() = savedSamples
    override val preview get() = snapshot
    override fun start(file: File, onError: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Нет разрешения на микрофон")
        }
        ContextCompat.startForegroundService(context, Intent(context, MicrophoneService::class.java))
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Микрофон не поддерживает PCM 16 кГц" }
        val device = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, 8192)).build()
        recorder = device
        check(device.state == AudioRecord.STATE_INITIALIZED)
        writer = WavPcmWriter(file)
        live = LiveSpeechPreview(context) { line, status, recognizing ->
            synchronized(previewLock) {
                snapshot = snapshot.copy(
                    lines = if (line == null) snapshot.lines else (snapshot.lines + line).takeLast(200),
                    status = status, recognizing = recognizing,
                )
            }
        }
        device.startRecording()
        check(device.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        running = true
        reader = Thread({
            val buffer = ShortArray(512)
            try {
                while (running) {
                    val generation = captureGeneration
                    val count = device.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (!running) break
                    check(count > 0) { "Ошибка чтения микрофона: $count" }
                    synchronized(frameLock) {
                        if (!paused && running && generation == captureGeneration) {
                            val start = savedSamples
                            checkNotNull(writer).write(buffer, count)
                            savedSamples += count
                            var maximum = 0
                            for (i in 0 until count) maximum = maxOf(maximum, kotlin.math.abs(buffer[i].toInt()))
                            peak = maximum
                            live?.offer(start, buffer, count)
                        }
                    }
                }
            } catch (error: Exception) {
                failure = error
                running = false
                // Callback не выполняется в reader: stop может ждать его завершения.
                CoroutineScope(Dispatchers.IO).launch { onError() }
            }
        }, "CatscriptorPcmCapture").also { it.start() }
    }
    override fun pause() = synchronized(frameLock) { captureGeneration++; paused = true; live?.pause(); Unit }
    override fun resume() = synchronized(frameLock) { captureGeneration++; paused = false }
    override fun amplitude() = peak
    override fun setLiveText(enabled: Boolean) { live?.enable(enabled) }
    override fun stop() {
        running = false
        runCatching { recorder?.stop() }
        reader?.join()
        reader = null
        try { writer?.close() } finally { writer = null }
        runBlocking { live?.close() }
        live = null
        failure?.let { throw IllegalStateException("Запись микрофона прервана", it) }
    }
    override fun release() {
        try { stop() } finally {
            recorder?.release(); recorder = null
            context.stopService(Intent(context, MicrophoneService::class.java))
        }
    }
}
