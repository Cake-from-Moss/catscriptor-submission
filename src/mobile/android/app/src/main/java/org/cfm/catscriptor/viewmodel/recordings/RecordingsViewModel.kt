package org.cfm.catscriptor.viewmodel.recordings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.cfm.catscriptor.domain.app.RecordingFilter
import org.cfm.catscriptor.domain.app.RecordingQuery
import org.cfm.catscriptor.domain.app.RecordingRepository
import org.cfm.catscriptor.domain.app.RecordingStatus
import org.cfm.catscriptor.domain.app.RecordingSummary

data class RecordingsUiState(
    val query: RecordingQuery = RecordingQuery(),
    val groups: List<RecordingGroup> = emptyList(),
    val loaded: Boolean = false,
    val renaming: RecordingSummary? = null,
    val exportingId: String? = null,
) {
    val isEmpty: Boolean get() = loaded && groups.isEmpty()

    /** Пустой список при активном поиске или фильтре — это «ничего не найдено», а не «нет записей». */
    val isFiltered: Boolean get() = query.text.isNotBlank() || query.filter != RecordingFilter.ALL
}

/** Куда вести по нажатию на строку: у неготовой записи расшифровки ещё нет. */
sealed interface RecordingOpenTarget {
    data class Transcript(val recordingId: String) : RecordingOpenTarget
    data object ProcessingDetails : RecordingOpenTarget
}

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingsViewModel(
    private val repository: RecordingRepository,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {
    private val _query = MutableStateFlow(RecordingQuery())

    /** Отдельный поток для поля поиска: текст в нём должен меняться синхронно с вводом. */
    val query: StateFlow<RecordingQuery> = _query
    private val dialogs = MutableStateFlow(Dialogs())

    private val groups = _query
        .flatMapLatest { repository.observe(it) }
        .map { groupRecordings(it, nowEpochMs(), zone()) }

    val state: StateFlow<RecordingsUiState> = combine(_query, groups, dialogs) { query, groups, dialogs ->
        RecordingsUiState(
            query = query,
            groups = groups,
            loaded = true,
            renaming = dialogs.renaming,
            exportingId = dialogs.exportingId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordingsUiState())

    fun onSearchChange(text: String) = _query.update { it.copy(text = text) }

    fun onFilterChange(filter: RecordingFilter) = _query.update { it.copy(filter = filter) }

    fun openTarget(summary: RecordingSummary): RecordingOpenTarget = when (summary.status) {
        RecordingStatus.Ready, RecordingStatus.AsrReady -> RecordingOpenTarget.Transcript(summary.id)
        else -> RecordingOpenTarget.ProcessingDetails
    }

    fun onRenameRequest(summary: RecordingSummary) = dialogs.update { it.copy(renaming = summary) }

    fun onRenameDismiss() = dialogs.update { it.copy(renaming = null) }

    fun onRenameConfirm(title: String) {
        val target = dialogs.value.renaming ?: return
        dialogs.update { it.copy(renaming = null) }
        val trimmed = title.trim()
        if (trimmed.isEmpty() || trimmed == target.title) return
        viewModelScope.launch { repository.rename(target.id, trimmed) }
    }

    /** Экспорт доступен только для готовой записи: у остальных нечего выгружать. */
    fun onExportRequest(summary: RecordingSummary) {
        if (summary.status == RecordingStatus.Ready) dialogs.update { it.copy(exportingId = summary.id) }
    }

    fun onExportDismiss() = dialogs.update { it.copy(exportingId = null) }

    /** Мягкое удаление; окончательно запись удаляет репозиторий, если не было «Отменить». */
    fun onDelete(summary: RecordingSummary) {
        viewModelScope.launch { repository.delete(summary.id) }
    }

    fun onUndoDelete(recordingId: String) {
        viewModelScope.launch { repository.restore(recordingId) }
    }

    /** Snackbar «Удалено · Отменить» закрылся без отмены: удаляем окончательно. */
    fun onDeleteConfirmed(recordingId: String) {
        viewModelScope.launch { repository.commitDelete(recordingId) }
    }

    private data class Dialogs(val renaming: RecordingSummary? = null, val exportingId: String? = null)
}
