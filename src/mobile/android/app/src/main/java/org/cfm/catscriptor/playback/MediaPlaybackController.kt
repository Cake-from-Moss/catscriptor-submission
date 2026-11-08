package org.cfm.catscriptor.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.cfm.catscriptor.domain.app.PlaybackController
import org.cfm.catscriptor.domain.app.PlaybackState

/**
 * Настоящий плеер экрана расшифровки (#20). Играет исходный файл записи через [AudioEngine].
 *
 * [audioUri] возвращает URI исходного файла по id записи или null, если записи нет. [scope]
 * должен работать в главном потоке: движок и его колбэки однопоточные. Позиция опрашивается
 * раз в [TICK_MS], пока идёт воспроизведение, — этого хватает для подсветки текущей реплики.
 */
class MediaPlaybackController(
    private val audioUri: (recordingId: String) -> String?,
    private val engineFactory: () -> AudioEngine,
    private val scope: CoroutineScope,
) : PlaybackController {
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var engine: AudioEngine? = null
    private var ticker: Job? = null

    override fun load(recordingId: String) {
        val current = _state.value
        // Повторный вход на тот же экран (например, после поворота) не сбрасывает позицию.
        if (current.recordingId == recordingId && engine != null) return
        releaseEngine()
        _state.value = PlaybackState(recordingId = recordingId)
        val uri = audioUri(recordingId) ?: return
        val created = engineFactory()
        engine = created
        created.open(
            uri = uri,
            onPrepared = { duration ->
                if (engine === created) {
                    _state.update { it.copy(durationMs = duration, isAvailable = duration > 0) }
                }
            },
            onError = {
                if (engine === created) {
                    stopTicker()
                    _state.update { it.copy(isAvailable = false, isPlaying = false) }
                }
            },
            onCompletion = {
                if (engine === created) {
                    stopTicker()
                    _state.update { it.copy(isPlaying = false, positionMs = it.durationMs) }
                }
            },
        )
    }

    override fun play() {
        val current = _state.value
        val player = engine
        if (player == null || !current.isAvailable || current.isPlaying) return
        // С конца записи воспроизведение начинается заново, как в системных плеерах.
        if (current.positionMs >= current.durationMs) {
            player.seekTo(0)
            _state.update { it.copy(positionMs = 0) }
        }
        player.start(current.speed)
        _state.update { it.copy(isPlaying = true) }
        startTicker(player)
    }

    override fun pause() {
        val player = engine ?: return
        player.pause()
        stopTicker()
        _state.update { it.copy(isPlaying = false, positionMs = player.positionMs().coerceIn(0, it.durationMs)) }
    }

    override fun seekTo(positionMs: Long) {
        val player = engine ?: return
        if (!_state.value.isAvailable) return
        val target = positionMs.coerceIn(0, _state.value.durationMs)
        player.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    override fun setSpeed(speed: Float) {
        require(speed > 0f && speed.isFinite())
        if (_state.value.isPlaying) engine?.setSpeed(speed)
        _state.update { it.copy(speed = speed) }
    }

    override fun release(recordingId: String) {
        if (_state.value.recordingId != recordingId) return
        releaseEngine()
        _state.value = PlaybackState()
    }

    private fun startTicker(player: AudioEngine) {
        stopTicker()
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (engine !== player) break
                _state.update { it.copy(positionMs = player.positionMs().coerceIn(0, it.durationMs)) }
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun releaseEngine() {
        stopTicker()
        engine?.release()
        engine = null
    }

    private companion object {
        const val TICK_MS = 100L
    }
}
