package org.cfm.catscriptor.audio.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import org.cfm.catscriptor.domain.app.CorrectionLevel
import org.cfm.catscriptor.native.models.LlamaTextCorrector
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cfm.catscriptor.MainActivity
import org.cfm.catscriptor.audio.AndroidAudioDecoder
import org.cfm.catscriptor.domain.app.ProcessingStage
import org.cfm.catscriptor.domain.pipeline.AudioInput

class AudioProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var notificationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var cancellationMessage = "Подготовка аудио отменена"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            if (job == null) {
                AudioProcessingController.publish(AudioProcessingState(AudioProcessingPhase.CANCELLED, message = cancellationMessage))
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (job != null) return START_NOT_STICKY
        val correctionOnly = intent?.action == ACTION_CORRECT_TEXT
        if (!correctionOnly && (intent?.action != ACTION_START || intent.data == null)) {
            if (AudioProcessingController.state.value.phase == AudioProcessingPhase.RUNNING) {
                AudioProcessingController.publish(AudioProcessingState(AudioProcessingPhase.FAILED,
                    message = "Не удалось запустить подготовку: отсутствует аудиодокумент"))
            }
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Подготовка аудио", NotificationManager.IMPORTANCE_LOW))
            val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            // Разрешение уведомлений не является условием запуска foreground service.
            startForeground(NOTIFICATION_ID, notification(if (correctionOnly) ProcessingStage.CORRECT_TEXT else ProcessingStage.PREPARE_AUDIO), type)
            // Уведомление повторяет текущий этап; без разрешения POST_NOTIFICATIONS notify() молча игнорируется.
            notificationJob = scope.launch {
                AudioProcessingController.state
                    .filter { it.phase == AudioProcessingPhase.RUNNING }
                    .map { it.stage }
                    .distinctUntilChanged()
                    .collect { manager.notify(NOTIFICATION_ID, notification(it)) }
            }
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:audioPreparation")
                .apply { acquire(6 * 60 * 60 * 1000L) }
            val input = AudioInput(intent?.data.toString())
            val correctionSeed = AudioProcessingController.state.value
            // Отсутствующий или неположительный extra — режим «авто».
            val speakerCount = intent?.getIntExtra(EXTRA_SPEAKER_COUNT, 0)?.takeIf { it > 0 }
            val correctionLevel = runCatching { CorrectionLevel.valueOf(intent?.getStringExtra(EXTRA_CORRECTION_LEVEL).orEmpty()) }
                .getOrDefault(CorrectionLevel.SMART)
            val manifest = if (correctionLevel == CorrectionLevel.FAST) LlamaTextCorrector.FAST_MANIFEST else LlamaTextCorrector.SMART_MANIFEST
            // Входим в try/finally до возврата из onStartCommand даже при немедленном уничтожении.
            job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var outcome = AudioProcessingState(AudioProcessingPhase.CANCELLED, message = cancellationMessage)
                try {
                    outcome = withContext(Dispatchers.IO) {
                        org.cfm.catscriptor.native.models.ModelExecutionGate.finalPipeline {
                            if (correctionOnly) {
                                TextCorrectionRunner(
                                    { LlamaTextCorrector.create(this@AudioProcessingService, manifest = manifest) },
                                    android.os.SystemClock::elapsedRealtime,
                                ).run(correctionSeed, AudioProcessingController::publish)
                            } else {
                                DiarizedDraftRunner(AndroidAudioDecoder(this@AudioProcessingService),
                                    { org.cfm.catscriptor.native.models.SherpaVoiceActivityDetector(this@AudioProcessingService) },
                                    { org.cfm.catscriptor.native.models.SherpaDiarizationBackend(this@AudioProcessingService, speakerCount) },
                                    { org.cfm.catscriptor.native.models.SherpaTranscriptionBackend(this@AudioProcessingService) },
                                    org.cfm.catscriptor.domain.pipeline.DefaultUtteranceMerger(),
                                    { LlamaTextCorrector.create(this@AudioProcessingService, manifest = manifest) },
                                    android.os.SystemClock::elapsedRealtime,
                                    logTiming = { android.util.Log.i("PipelineTiming", it) },
                                    textCorrectionEnabled = correctionLevel != CorrectionLevel.OFF,
                                ).run(input, AudioProcessingController::publish)
                            }
                        }
                    }
                    ensureActive()
                } catch (_: CancellationException) {
                    outcome = AudioProcessingController.state.value.copy(phase = AudioProcessingPhase.CANCELLED, message = cancellationMessage)
                } catch (error: Exception) {
                    // Упавший этап уже опубликован запускателем в state.stage/state.stages.
                    logProcessingError("Ошибка обработки аудио", error)
                    outcome = AudioProcessingController.state.value.copy(phase = AudioProcessingPhase.FAILED, message = "Не удалось обработать аудио")
                } finally {
                    // Декодер уже закрыл PCM и временные файлы; здесь нет приостанавливающих операций.
                    notificationJob?.cancel()
                    notificationJob = null
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    job = null
                    stopSelf()
                    AudioProcessingController.publish(outcome)
                }
            }
        } catch (error: Exception) {
            logProcessingError("Ошибка инициализации foreground service", error)
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            AudioProcessingController.publish(AudioProcessingState(AudioProcessingPhase.FAILED, message = "Не удалось запустить подготовку аудио"))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancellationMessage = "Подготовка отменена: истекло время фоновой обработки"
        job?.cancel()
        if (job == null && AudioProcessingController.state.value.phase == AudioProcessingPhase.RUNNING) {
            AudioProcessingController.publish(AudioProcessingState(AudioProcessingPhase.CANCELLED, message = cancellationMessage))
        }
        releaseWakeLock()
        // Системный тайм-аут требует немедленного выхода из foreground, очистка продолжается в job.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        // При активном job терминальное состояние публикуется только после очистки декодера.
        if (job == null && AudioProcessingController.state.value.phase == AudioProcessingPhase.RUNNING) {
            AudioProcessingController.publish(AudioProcessingState(AudioProcessingPhase.CANCELLED, message = cancellationMessage))
        }
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun notification(stage: ProcessingStage): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, AudioProcessingService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Обработка записи на устройстве")
            .setContentText("Этап ${stage.ordinal + 1} из ${ProcessingStage.entries.size}: ${stageLabel(stage)}")
            .setContentIntent(open)
            .setOngoing(true)
            .setProgress(ProcessingStage.entries.size, stage.ordinal, false)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Отмена", cancel).build())
            .build()
    }

    private fun stageLabel(stage: ProcessingStage): String = when (stage) {
        ProcessingStage.PREPARE_AUDIO -> "подготовка аудио"
        ProcessingStage.DETECT_SPEECH -> "выделение речи"
        ProcessingStage.DIARIZE -> "определение говорящих"
        ProcessingStage.TRANSCRIBE -> "распознавание"
        ProcessingStage.CORRECT_TEXT -> "исправление текста"
    }

    companion object {
        internal const val ACTION_START = "org.cfm.catscriptor.audio.START"
        internal const val ACTION_CORRECT_TEXT = "org.cfm.catscriptor.audio.CORRECT_TEXT"
        internal const val EXTRA_SPEAKER_COUNT = "org.cfm.catscriptor.audio.SPEAKER_COUNT"
        internal const val EXTRA_CORRECTION_LEVEL = "org.cfm.catscriptor.audio.CORRECTION_LEVEL"
        internal const val ACTION_CANCEL = "org.cfm.catscriptor.audio.CANCEL"
        private const val CHANNEL = "audio_preparation"
        private const val NOTIFICATION_ID = 1001
    }
}
