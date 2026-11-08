package org.cfm.catscriptor.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.core.net.toUri

/**
 * [AudioEngine] поверх системного [MediaPlayer]: читает исходный файл записи (SAF-документ с
 * постоянным разрешением или content:// нашего FileProvider) без декодирования в PCM.
 * Создавать в главном потоке: колбэки MediaPlayer приходят в Looper потока создания.
 */
class MediaPlayerEngine(private val context: Context) : AudioEngine {
    private var player: MediaPlayer? = null

    override fun open(uri: String, onPrepared: (Long) -> Unit, onError: () -> Unit, onCompletion: () -> Unit) {
        release()
        val created = MediaPlayer()
        player = created
        try {
            created.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            created.setDataSource(context.applicationContext, uri.toUri())
            created.setOnPreparedListener { onPrepared(it.duration.toLong().coerceAtLeast(0)) }
            created.setOnCompletionListener { onCompletion() }
            created.setOnErrorListener { _, _, _ ->
                onError()
                true
            }
            created.prepareAsync()
        } catch (_: Exception) {
            // Файл удалён, разрешение SAF отозвано или формат не поддерживается.
            release()
            onError()
        }
    }

    override fun start(speed: Float) {
        val current = player ?: return
        // Установка PlaybackParams с ненулевой скоростью сама запускает воспроизведение,
        // поэтому скорость задаётся здесь, вместо отдельного start().
        current.playbackParams = current.playbackParams.setSpeed(speed)
        if (!current.isPlaying) current.start()
    }

    override fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
    }

    override fun seekTo(positionMs: Long) {
        // SEEK_CLOSEST: переход к началу реплики должен попадать точно, а не на ближайший ключевой кадр.
        player?.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST)
    }

    /** На паузе PlaybackParams запустили бы воспроизведение; контроллер вызывает только во время игры. */
    override fun setSpeed(speed: Float) {
        val current = player ?: return
        if (current.isPlaying) current.playbackParams = current.playbackParams.setSpeed(speed)
    }

    override fun positionMs(): Long = player?.currentPosition?.toLong() ?: 0L

    override fun release() {
        val current = player ?: return
        player = null
        current.setOnPreparedListener(null)
        current.setOnCompletionListener(null)
        current.setOnErrorListener(null)
        current.release()
    }
}
