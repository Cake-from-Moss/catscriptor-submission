package org.cfm.catscriptor.ui.transcript

import android.content.ClipData
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.cfm.catscriptor.R
import org.cfm.catscriptor.app.AppGraph
import org.cfm.catscriptor.ui.export.ExportSheetRoute
import org.cfm.catscriptor.ui.navigation.AppNavigator
import org.cfm.catscriptor.ui.navigation.LocalAppSnackbar
import org.cfm.catscriptor.ui.navigation.ScopedViewModels
import org.cfm.catscriptor.ui.recordings.TextInputDialog
import org.cfm.catscriptor.ui.recordings.speakerName
import org.cfm.catscriptor.viewmodel.transcript.TranscriptDialog
import org.cfm.catscriptor.viewmodel.transcript.TranscriptViewModel

@Composable
fun TranscriptRoute(graph: AppGraph, navigator: AppNavigator, recordingId: String) {
    val viewModel = viewModel(key = "transcript-$recordingId") {
        TranscriptViewModel(recordingId, graph.recordings, graph.playback)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    var correcting by remember { mutableStateOf(false) }
    val copiedMessage = stringResource(R.string.transcript_copied)

    // Освобождение плеера — в TranscriptViewModel.onCleared: при повороте экран уходит из композиции,
    // но воспроизведение должно продолжиться.
    LaunchedEffect(viewModel) { viewModel.onEnter() }

    // Назад сначала закрывает поиск, и только потом экран.
    BackHandler(enabled = state.searchOpen, onBack = viewModel::onSearchClose)

    val actions = remember(viewModel, navigator, correcting) {
        TranscriptActions(
            onBack = navigator.back,
            onShare = viewModel::onExportOpen,
            onSearchOpen = viewModel::onSearchOpen,
            onSearchClose = viewModel::onSearchClose,
            onSearchQueryChange = viewModel::onSearchQueryChange,
            onNextMatch = viewModel::onNextMatch,
            onPreviousMatch = viewModel::onPreviousMatch,
            onSpeakerClick = viewModel::onSpeakerRenameRequest,
            onLineClick = viewModel::onLineClick,
            onLineLongPress = viewModel::onLineLongPress,
            onUserScroll = viewModel::onUserScroll,
            onReturnToCurrent = viewModel::onReturnToCurrent,
            onSaveNote = viewModel::saveNote,
            onDeleteNote = viewModel::deleteNote,
            defaultCorrectionLevel = graph.settings.settings.value.correctionLevel,
            onCorrectText = { level ->
                if (correcting) return@TranscriptActions
                correcting = true
                scope.launch {
                    snackbar.show(message = "Пунктуация и правка запущены")
                    try {
                        graph.processing.correctText(recordingId, level)
                        snackbar.show(message = "Текст обновлён")
                    } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                    catch (error: Exception) { snackbar.show(message = error.message ?: "Не удалось исправить текст") }
                    finally { correcting = false }
                }
            },
            player = PlayerActions(
                onPlayPause = viewModel::onPlayPause,
                onSeek = viewModel::onSeek,
                onSpeedCycle = viewModel::onSpeedCycle,
                onPreviousLine = viewModel::onPreviousLine,
                onNextLine = viewModel::onNextLine,
                onToggleFollow = viewModel::onToggleFollow,
            ),
        )
    }

    TranscriptScreen(state = state, searchQuery = searchQuery, actions = actions)

    val document = state.document
    state.lineMenu?.let { line ->
        LineActionsSheet(
            line = line,
            speaker = document?.speakers?.firstOrNull { it.id == line.speakerId },
            onEditText = { viewModel.onEditTextRequest(line) },
            onChangeSpeaker = { viewModel.onChangeSpeakerRequest(line) },
            onCopy = {
                viewModel.onLineMenuDismiss()
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(line.speakerId, line.text)))
                    // С Android 13 система сама показывает подтверждение копирования.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) snackbar.show(copiedMessage)
                }
            },
            onDismiss = viewModel::onLineMenuDismiss,
        )
    }

    when (val dialog = state.dialog) {
        is TranscriptDialog.RenameSpeaker -> TextInputDialog(
            title = stringResource(R.string.transcript_rename_speaker_title),
            label = stringResource(R.string.transcript_rename_speaker_label),
            initialValue = speakerName(dialog.speaker, dialog.speaker.id),
            supportingText = stringResource(R.string.transcript_rename_speaker_hint),
            allowEmpty = true,
            onConfirm = { name -> viewModel.onSpeakerRenameConfirm(dialog.speaker.id, name) },
            onDismiss = viewModel::onDialogDismiss,
        )
        is TranscriptDialog.EditText -> TextInputDialog(
            title = stringResource(R.string.transcript_edit_title),
            label = stringResource(R.string.transcript_edit_title),
            initialValue = dialog.line.text,
            supportingText = stringResource(R.string.transcript_edit_hint),
            singleLine = false,
            onConfirm = { text -> viewModel.onEditTextConfirm(dialog.line.id, text) },
            onDismiss = viewModel::onDialogDismiss,
        )
        is TranscriptDialog.ChangeSpeaker -> ChangeSpeakerDialog(
            line = dialog.line,
            speakers = document?.speakers.orEmpty(),
            onConfirm = { speakerId -> viewModel.onChangeSpeakerConfirm(dialog.line.id, speakerId) },
            onDismiss = viewModel::onDialogDismiss,
        )
        null -> Unit
    }

    if (state.exportOpen && document != null && document.asrDraft == null) {
        ScopedViewModels(key = "export-$recordingId") {
            ExportSheetRoute(graph = graph, recordingId = recordingId, onDismiss = viewModel::onExportDismiss)
        }
    }

    state.noteError?.let { message ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = viewModel::dismissNoteError,
            title = { androidx.compose.material3.Text("Не удалось сохранить пометку") },
            text = { androidx.compose.material3.Text(message) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = viewModel::dismissNoteError) {
                    androidx.compose.material3.Text("Понятно")
                }
            },
        )
    }
}
