package org.cfm.catscriptor.domain.app

import kotlinx.coroutines.flow.Flow

enum class RecordingSource { MICROPHONE, FILE }

/** Состояние записи в библиотеке. Подробности обработки лежат в [ProcessingJob]. */
sealed interface RecordingStatus {
    data object Ready : RecordingStatus
    data object AsrReady : RecordingStatus
    data object Queued : RecordingStatus
    data class Processing(val fraction: Float, val stage: ProcessingStage) : RecordingStatus
    data class Failed(val stage: ProcessingStage) : RecordingStatus
}

/**
 * Строка списка записей. [speakerCount] равен null, пока диаризация не завершена.
 * [previewSpeakerId] и [previewText] — начало расшифровки для блока «Недавние».
 */
data class RecordingSummary(
    val id: String,
    val title: String,
    val source: RecordingSource,
    val createdAtEpochMs: Long,
    val durationMs: Long,
    val speakerCount: Int?,
    val previewSpeakerId: String?,
    val previewSpeakerName: String?,
    val previewText: String?,
    val status: RecordingStatus,
    val textCorrected: Boolean = false,
)

enum class RecordingFilter { ALL, READY, PROCESSING }

/** Поиск идёт по названию и по тексту расшифровок; пустой [text] означает «без поиска». */
data class RecordingQuery(val text: String = "", val filter: RecordingFilter = RecordingFilter.ALL)

/** Спикер расшифровки. [displayName] равен null, пока пользователь не задал имя. */
data class SpeakerInfo(val id: String, val displayName: String?, val shareFraction: Float)

/** Личная пометка к позиции аудио; не является частью распознанного текста или JSON экспорта. */
data class RecordingNote(val id: String, val positionMs: Long, val text: String)

/** Реплика для показа и правки. Таймкоды остаются исходными таймкодами ASR. */
data class TranscriptLine(
    val id: String,
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/** [lines] упорядочены по startMs, как и реплики в pipeline.Transcript. */
data class TranscriptDocument(
    val recordingId: String,
    val title: String,
    val createdAtEpochMs: Long,
    val durationMs: Long,
    val speakers: List<SpeakerInfo>,
    val lines: List<TranscriptLine>,
    val bookmarksMs: List<Long> = emptyList(),
    val asrDraft: List<org.cfm.catscriptor.domain.pipeline.AsrSegment>? = null,
    /** Спикеры реплик черновика по индексам [asrDraft]; null — диаризация не выполнялась. */
    val asrDraftSpeakers: List<String>? = null,
    val notes: List<RecordingNote> = emptyList(),
    val textCorrected: Boolean = false,
) {
    init {
        require(asrDraft == null || (lines.isEmpty() && speakers.isEmpty()))
        require(asrDraftSpeakers == null || asrDraftSpeakers.size == asrDraft?.size) {
            "Speakers must match the draft one-to-one"
        }
        // Поиск текущей реплики в плеере бинарный и опирается на этот порядок.
        require(lines.zipWithNext().all { (a, b) -> a.startMs <= b.startMs }) {
            "Lines must be ordered by start time"
        }
    }
}

/**
 * Библиотека записей. Реализация сама хранит данные; UI только подписывается на потоки.
 * Методы правки меняют только пользовательский слой и не трогают исходные таймкоды ASR.
 */
interface RecordingRepository {
    fun observe(query: RecordingQuery = RecordingQuery()): Flow<List<RecordingSummary>>

    /** Выдаёт null, если записи нет или её расшифровка ещё не готова. */
    fun observeTranscript(recordingId: String): Flow<TranscriptDocument?>

    suspend fun rename(recordingId: String, title: String)

    /**
     * Мягкое удаление: запись пропадает из списка, но её можно вернуть через [restore],
     * пока UI не вызовет [commitDelete] (Snackbar «Удалено · Отменить» закрылся без отмены).
     * Если процесс умер раньше, при следующем запуске такие записи нужно удалить окончательно.
     */
    suspend fun delete(recordingId: String)
    suspend fun restore(recordingId: String)

    /** Окончательно удаляет запись и её файлы; после этого [restore] ничего не вернёт. */
    suspend fun commitDelete(recordingId: String)

    suspend fun renameSpeaker(recordingId: String, speakerId: String, displayName: String)
    suspend fun editLineText(recordingId: String, lineId: String, text: String)
    suspend fun changeLineSpeaker(recordingId: String, lineId: String, speakerId: String)

    suspend fun saveNote(recordingId: String, noteId: String?, positionMs: Long, text: String)
    suspend fun deleteNote(recordingId: String, noteId: String)
}
