package org.cfm.catscriptor.domain.app

import kotlinx.coroutines.flow.StateFlow

/** Реплика Live-текста. Спикер предварительный; [isFinal] = false означает гипотезу модели. */
data class LiveLine(
    val id: String,
    val speakerId: String,
    val startMs: Long,
    val text: String,
    val isFinal: Boolean,
)

sealed interface RecorderState {
    data object Idle : RecorderState

    /**
     * [levelDbfs] обновляется примерно 10 раз в секунду: у MediaRecorder это пик, не RMS;
     * сглаживание индикатора делает UI. [peakDbfs] нужен для распознавания клиппинга.
     * [speechDetected] — сигнал VAD; без VAD всегда true, чтобы не имитировать «Тишину».
     */
    data class Active(
        val elapsedMs: Long,
        val paused: Boolean,
        val levelDbfs: Float,
        val peakDbfs: Float,
        val speechDetected: Boolean,
        val marksMs: List<Long>,
        val liveLines: List<LiveLine>,
        val pausedByInterruption: Boolean = false,
        val liveStatus: String = "",
        val liveRecognizing: Boolean = false,
        val notes: List<RecordingNote> = emptyList(),
    ) : RecorderState
}

sealed interface StartRecordingResult {
    data object Started : StartRecordingResult
    data class LowStorage(val minutesAvailable: Int) : StartRecordingResult
    data object Unavailable : StartRecordingResult
}

/**
 * Запись с микрофона (#21). Разрешение RECORD_AUDIO запрашивает UI до вызова [start].
 * [isAvailable] = false означает, что запись не реализована в этой сборке, и разрешение не нужно.
 */
interface RecorderController {
    val isAvailable: Boolean
    val state: StateFlow<RecorderState>

    suspend fun start(liveText: Boolean): StartRecordingResult

    /** Останавливает запись и ставит её в очередь обработки; возвращает id новой записи. */
    suspend fun stop(): String?
    /** Удаляет текущую сессию без импорта и финального распознавания. */
    suspend fun cancel()
    fun pause()
    fun resume()
    fun setLiveText(enabled: Boolean)
    fun addMark()
    fun addNote(text: String, positionMs: Long? = null)
    fun deleteNote(noteId: String)
}
