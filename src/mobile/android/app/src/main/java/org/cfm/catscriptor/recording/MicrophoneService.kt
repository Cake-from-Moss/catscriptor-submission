package org.cfm.catscriptor.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.cfm.catscriptor.MainActivity

/** Удерживает разрешённую системой сессию микрофона при сворачивании экрана. */
class MicrophoneService : Service() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("microphone", "Запись микрофона", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, "microphone")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Идёт запись разговора")
            .setContentText("Откройте CatScriptor, чтобы остановить или отменить запись")
            .setContentIntent(open).setOngoing(true).build()
        ServiceCompat.startForeground(this, 203, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}
