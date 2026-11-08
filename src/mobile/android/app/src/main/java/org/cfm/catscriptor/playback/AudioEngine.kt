package org.cfm.catscriptor.playback

/**
 * Минимальный аудиодвижок для [MediaPlaybackController]. Реализация для устройства —
 * [MediaPlayerEngine]; в JVM-тестах подставляется фейк. Все методы и колбэки — в главном потоке.
 */
interface AudioEngine {
    /**
     * Асинхронно открывает [uri]. Ровно один из колбэков вызывается после подготовки:
     * [onPrepared] с длительностью в мс или [onError].
     */
    fun open(uri: String, onPrepared: (durationMs: Long) -> Unit, onError: () -> Unit, onCompletion: () -> Unit)
    fun start(speed: Float)
    fun pause()
    fun seekTo(positionMs: Long)
    /** Меняет скорость; вызывать только во время воспроизведения (см. [MediaPlayerEngine.setSpeed]). */
    fun setSpeed(speed: Float)
    fun positionMs(): Long
    fun release()
}
