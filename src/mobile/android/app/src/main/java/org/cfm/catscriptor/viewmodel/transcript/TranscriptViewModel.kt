package org.cfm.catscriptor.viewmodel.transcript

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.cfm.catscriptor.domain.app.PlaybackController
import org.cfm.catscriptor.domain.app.PlaybackState
import org.cfm.catscriptor.domain.app.RecordingRepository
import org.cfm.catscriptor.domain.app.SpeakerInfo
import org.cfm.catscriptor.domain.app.TranscriptDocument
import org.cfm.catscriptor.domain.app.TranscriptLine

/** Диалоги экрана расшифровки; одновременно открыт не больше одного. */
sealed interface TranscriptDialog {
    data class RenameSpeaker(val speaker: SpeakerInfo) : TranscriptDialog
    data class EditText(val line: TranscriptLine) : TranscriptDialog
    data class ChangeSpeaker(val line: TranscriptLine) : TranscriptDialog
}

/** Поиск по тексту: [focusedIndex] указывает на активное совпадение в [matchIds]. */
data class TranscriptSearch(val matchIds: List<String> = emptyList(), val focusedIndex: Int = 0) {
    val focusedLineId: String? get() = matchIds.getOrNull(focusedIndex)
}

data class TranscriptUiState(
    val loading: Boolean = true,
    val document: TranscriptDocument? = null,
    /** Состояние плеера для этой записи; чужое состояние общего плеера сюда не попадает. */
    val playback: PlaybackState = PlaybackState(),
    val currentLineIndex: Int = -1,
    val followPlayback: Boolean = true,
    val searchOpen: Boolean = false,
    val search: TranscriptSearch = TranscriptSearch(),
    val lineMenu: TranscriptLine? = null,
    val dialog: TranscriptDialog? = null,
    val exportOpen: Boolean = false,
    val noteError: String? = null,
)

class TranscriptViewModel(
    private val recordingId: String,
    private val repository: RecordingRepository,
    private val playback: PlaybackController,
) : ViewModel() {
    private val ui = MutableStateFlow(LocalState())
    private val _searchQuery = MutableStateFlow("")

    /** Отдельный поток для поля поиска: текст в нём должен меняться синхронно с вводом. */
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val document = repository.observeTranscript(recordingId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, LOADING)

    val state: StateFlow<TranscriptUiState> =
        combine(document, playback.state, ui, _searchQuery) { doc, player, local, query ->
            val loaded = doc as? TranscriptDocument
            val lines = loaded?.lines.orEmpty()
            val ownPlayer = if (player.recordingId == recordingId) {
                player.copy(durationMs = player.durationMs.takeIf { it > 0 } ?: loaded?.durationMs ?: 0)
            } else {
                PlaybackState(recordingId = recordingId, durationMs = loaded?.durationMs ?: 0, isAvailable = false)
            }
            val matches = searchMatches(lines, query)
            TranscriptUiState(
                loading = doc === LOADING,
                document = loaded,
                playback = ownPlayer,
                currentLineIndex = if (ownPlayer.isAvailable) currentLineIndex(lines, ownPlayer.positionMs) else -1,
                followPlayback = local.followPlayback,
                searchOpen = local.searchOpen,
                search = TranscriptSearch(
                    matchIds = matches,
                    focusedIndex = local.focusedMatch.coerceIn(0, (matches.size - 1).coerceAtLeast(0)),
                ),
                lineMenu = local.lineMenu,
                dialog = local.dialog,
                exportOpen = local.exportOpen,
                noteError = local.noteError,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranscriptUiState())

    /** Экран стал видимым: плеер загружает аудио этой записи. */
    fun onEnter() = playback.load(recordingId)

    /**
     * Плеер освобождается вместе с ViewModel: её хранилище (ScopedViewModels) очищается, когда экран
     * закрыт по-настоящему, но переживает поворот — воспроизведение и позиция при повороте сохраняются.
     */
    override fun onCleared() = playback.release(recordingId)

    private val lines: List<TranscriptLine> get() = state.value.document?.lines.orEmpty()
    private val player: PlaybackState get() = state.value.playback

    // Плеер

    fun onLineClick(line: TranscriptLine) {
        if (!player.isAvailable) return
        playback.seekTo(line.startMs)
        playback.play()
    }

    fun onPlayPause() {
        if (!player.isAvailable) return
        if (player.isPlaying) playback.pause() else playback.play()
    }

    fun onSeek(positionMs: Long) {
        if (!player.isAvailable) return
        playback.seekTo(positionMs.coerceIn(0, player.durationMs))
    }

    fun onSpeedCycle() {
        if (!player.isAvailable) return
        playback.setSpeed(nextSpeed(player.speed))
    }

    fun onPreviousLine() {
        if (!player.isAvailable) return
        playback.seekTo(previousLineStart(lines, player.positionMs))
    }

    fun onNextLine() {
        if (!player.isAvailable) return
        nextLineStart(lines, player.positionMs)?.let(playback::seekTo)
    }

    fun onToggleFollow() = ui.update { it.copy(followPlayback = !it.followPlayback) }

    /** Пользователь сам прокрутил список: автопрокрутка больше не мешает читать. */
    fun onUserScroll() {
        if (ui.value.followPlayback) ui.update { it.copy(followPlayback = false) }
    }

    fun onReturnToCurrent() = ui.update { it.copy(followPlayback = true) }

    // Поиск

    fun onSearchOpen() = ui.update { it.copy(searchOpen = true) }

    fun onSearchClose() {
        _searchQuery.value = ""
        ui.update { it.copy(searchOpen = false, focusedMatch = 0) }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        // Переход к совпадению — это ручная навигация: автопрокрутка увела бы список обратно.
        ui.update { it.copy(focusedMatch = 0, followPlayback = if (query.isBlank()) it.followPlayback else false) }
    }

    fun onNextMatch() = moveMatch(+1)

    fun onPreviousMatch() = moveMatch(-1)

    private fun moveMatch(delta: Int) {
        val count = state.value.search.matchIds.size
        if (count == 0) return
        ui.update { it.copy(focusedMatch = (it.focusedMatch + delta).mod(count), followPlayback = false) }
    }

    // Правка

    fun onLineLongPress(line: TranscriptLine) = ui.update { it.copy(lineMenu = line) }

    fun onLineMenuDismiss() = ui.update { it.copy(lineMenu = null) }

    fun onEditTextRequest(line: TranscriptLine) =
        ui.update { it.copy(lineMenu = null, dialog = TranscriptDialog.EditText(line)) }

    fun onChangeSpeakerRequest(line: TranscriptLine) =
        ui.update { it.copy(lineMenu = null, dialog = TranscriptDialog.ChangeSpeaker(line)) }

    fun onSpeakerRenameRequest(speaker: SpeakerInfo) =
        ui.update { it.copy(dialog = TranscriptDialog.RenameSpeaker(speaker)) }

    fun onDialogDismiss() = ui.update { it.copy(dialog = null) }

    fun onEditTextConfirm(lineId: String, text: String) {
        if ((document.value as? TranscriptDocument)?.asrDraft != null) return
        onDialogDismiss()
        val normalized = text.trim()
        if (normalized.isEmpty() || lines.firstOrNull { it.id == lineId }?.text == normalized) return
        viewModelScope.launch { repository.editLineText(recordingId, lineId, normalized) }
    }

    fun onChangeSpeakerConfirm(lineId: String, speakerId: String) {
        if ((document.value as? TranscriptDocument)?.asrDraft != null) return
        onDialogDismiss()
        if (lines.firstOrNull { it.id == lineId }?.speakerId == speakerId) return
        viewModelScope.launch { repository.changeLineSpeaker(recordingId, lineId, speakerId) }
    }

    /** Пустое имя возвращает подпись по умолчанию «Спикер N». */
    fun onSpeakerRenameConfirm(speakerId: String, name: String) {
        if ((document.value as? TranscriptDocument)?.asrDraft != null) return
        onDialogDismiss()
        viewModelScope.launch { repository.renameSpeaker(recordingId, speakerId, name.trim()) }
    }

    // Экспорт

    fun saveNote(noteId: String?, positionMs: Long, text: String) {
        if (state.value.document == null) return
        mutateNotes { repository.saveNote(recordingId, noteId, positionMs, text) }
    }

    fun deleteNote(noteId: String) {
        mutateNotes { repository.deleteNote(recordingId, noteId) }
    }

    fun dismissNoteError() = ui.update { it.copy(noteError = null) }

    private fun mutateNotes(change: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                change()
                dismissNoteError()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                ui.update { it.copy(noteError = "Не удалось сохранить изменения пометок. Попробуйте ещё раз.") }
            }
        }
    }

    fun onExportOpen() {
        if ((document.value as? TranscriptDocument)?.asrDraft != null) return
        ui.update { it.copy(exportOpen = true) }
    }

    fun onExportDismiss() = ui.update { it.copy(exportOpen = false) }

    private data class LocalState(
        val followPlayback: Boolean = true,
        val searchOpen: Boolean = false,
        val focusedMatch: Int = 0,
        val lineMenu: TranscriptLine? = null,
        val dialog: TranscriptDialog? = null,
        val exportOpen: Boolean = false,
        val noteError: String? = null,
    )

    private companion object {
        /** Маркер «ещё не загружено», чтобы отличить загрузку от отсутствующей расшифровки (null). */
        val LOADING = Any()
    }
}
