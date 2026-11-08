package org.cfm.catscriptor.ui.recordings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.cfm.catscriptor.R
import org.cfm.catscriptor.app.AppGraph
import org.cfm.catscriptor.ui.export.ExportSheetRoute
import org.cfm.catscriptor.ui.navigation.AppNavigator
import org.cfm.catscriptor.ui.navigation.LocalAppSnackbar
import org.cfm.catscriptor.ui.navigation.ScopedViewModels
import org.cfm.catscriptor.ui.navigation.TopTab
import org.cfm.catscriptor.viewmodel.recordings.RecordingOpenTarget
import org.cfm.catscriptor.viewmodel.recordings.RecordingsViewModel

/** Окно «Отменить» после удаления записи. */
private const val UNDO_TIMEOUT_MS = 5_000L

@Composable
fun RecordingsRoute(graph: AppGraph, navigator: AppNavigator) {
    val viewModel = viewModel(key = "recordings") { RecordingsViewModel(graph.recordings) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    var correctionTarget by remember { mutableStateOf<String?>(null) }
    var correctingId by remember { mutableStateOf<String?>(null) }
    val deletedMessage = stringResource(R.string.recordings_deleted)
    val undoLabel = stringResource(R.string.recordings_undo)

    val rowActions = remember(viewModel, navigator, snackbar, deletedMessage, undoLabel, correctingId) {
        RecordingRowActions(
            onOpen = { summary ->
                when (val target = viewModel.openTarget(summary)) {
                    is RecordingOpenTarget.Transcript -> navigator.openTranscript(target.recordingId)
                    RecordingOpenTarget.ProcessingDetails -> navigator.showProcessingDetails()
                }
            },
            onRename = viewModel::onRenameRequest,
            onCorrect = { summary -> if (correctingId == null) correctionTarget = summary.id },
            onExport = viewModel::onExportRequest,
            onDelete = { summary ->
                scope.launch {
                    try {
                        graph.recordings.delete(summary.id)
                        snackbar.show(
                            message = deletedMessage,
                            actionLabel = undoLabel,
                            onAction = { viewModel.onUndoDelete(summary.id) },
                            timeoutMs = UNDO_TIMEOUT_MS,
                        )
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { snackbar.show(message = "Не удалось удалить запись") }
                }
            },
        )
    }

    RecordingsScreen(
        state = state,
        query = query,
        onSearchChange = viewModel::onSearchChange,
        onFilterChange = viewModel::onFilterChange,
        onGoToRecord = { navigator.openTab(TopTab.HOME) },
        onOpenSettings = navigator.openSettings,
        rowActions = rowActions,
    )

    state.renaming?.let { recording ->
        TextInputDialog(
            title = stringResource(R.string.recordings_rename_title),
            label = stringResource(R.string.recordings_rename_label),
            initialValue = recording.title,
            onConfirm = viewModel::onRenameConfirm,
            onDismiss = viewModel::onRenameDismiss,
        )
    }

    correctionTarget?.let { id ->
        CorrectionDialog(
            defaultLevel = graph.settings.settings.value.correctionLevel,
            onDismiss = { correctionTarget = null },
            onConfirm = { level ->
                correctionTarget = null
                correctingId = id
                scope.launch {
                    snackbar.show(message = "Пунктуация и правка запущены")
                    try {
                        graph.processing.correctText(id, level)
                        snackbar.show(message = "Текст обновлён")
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { snackbar.show(message = error.message ?: "Не удалось исправить текст") }
                    finally { correctingId = null }
                }
            },
        )
    }

    state.exportingId?.let { recordingId ->
        ScopedViewModels(key = "export-$recordingId") {
            ExportSheetRoute(graph = graph, recordingId = recordingId, onDismiss = viewModel::onExportDismiss)
        }
    }
}
