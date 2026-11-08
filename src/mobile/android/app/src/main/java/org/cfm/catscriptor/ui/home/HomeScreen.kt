package org.cfm.catscriptor.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.cfm.catscriptor.R
import org.cfm.catscriptor.domain.app.ProcessingStage
import org.cfm.catscriptor.ui.components.AppIcons
import org.cfm.catscriptor.ui.theme.StenografDimens
import org.cfm.catscriptor.ui.theme.StenografMotion
import org.cfm.catscriptor.ui.theme.StenografShapes
import org.cfm.catscriptor.ui.theme.StenografType
import org.cfm.catscriptor.viewmodel.home.HomeContent
import org.cfm.catscriptor.viewmodel.home.HomeUiState
import org.cfm.catscriptor.viewmodel.home.RecorderSignal
import org.cfm.catscriptor.domain.app.RecordingSummary

/** Действия главного экрана; реализации подставляет [HomeRoute]. */
class HomeActions(
    val onRecord: () -> Unit,
    val onStop: () -> Unit,
    val onToggleLiveText: () -> Unit,
    val onUploadFile: () -> Unit,
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onAddMark: () -> Unit,
    val onAddNote: (String, Long?) -> Unit = { _, _ -> },
    val onDeleteNote: (String) -> Unit = {},
    val onOpenSettings: () -> Unit,
    val onOpenRecording: (String) -> Unit,
    val onOpenAllRecordings: () -> Unit,
    val onExpandJob: (String) -> Unit,
    val onCollapseJob: () -> Unit,
    val onCancelJob: (String) -> Unit,
    val onRetryJob: (String) -> Unit,
    val onSkipStage: (String, ProcessingStage) -> Unit,
    val onCancelRecording: () -> Unit = {},
    val onDeleteRecording: (String) -> Unit = {},
    val onRenameRecording: (RecordingSummary) -> Unit = {},
    val onCorrectText: (RecordingSummary) -> Unit = {},
)

/**
 * Главный экран без состояния. Шапка, «пилюля» и ряд кнопок закреплены сверху, чтобы «стоп»
 * всегда был под рукой; содержимое ниже прокручивается под плавающей навигацией.
 * [signal] — лямбда, чтобы уровень и таймер перерисовывали только свои элементы.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    signal: () -> RecorderSignal?,
    micAccess: MicAccess,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    var showModelsDetails by rememberSaveable { mutableStateOf(false) }
    var showCancelRecording by rememberSaveable { mutableStateOf(false) }
    val recording = state.recording

    // Последний элемент должен докручиваться выше плавающей навигации: её высота + отступы.
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val overlayHeight = navBottom + StenografDimens.BottomBarHeight + StenografDimens.BottomBarMargin
    val contentPadding = PaddingValues(
        start = StenografDimens.ScreenPadding,
        end = StenografDimens.ScreenPadding,
        bottom = overlayHeight + 16.dp,
    )

    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        HomeHeader(onOpenSettings = actions.onOpenSettings)
        RecordPill(
            recording = recording != null,
            paused = recording?.paused == true,
            queueBusy = state.queueBusy,
            // Если запись не реализована в сборке, разрешение не нужно и не упоминается.
            micAccess = if (state.recorderAvailable) micAccess else MicAccess.GRANTED,
            signal = signal,
            onStart = actions.onRecord,
            onStop = actions.onStop,
            modifier = Modifier.padding(horizontal = StenografDimens.ScreenPadding),
        )
        Spacer(Modifier.height(12.dp))
        ActionRow(
            liveTextEnabled = state.liveTextEnabled,
            recording = recording != null,
            paused = recording?.paused == true,
            actions = actions,
            modifier = Modifier.padding(horizontal = StenografDimens.ScreenPadding),
        )
        Spacer(Modifier.height(8.dp))
        if (recording != null) {
            RecordingNotes(
                notes = recording.notes,
                signal = signal,
                onAddNote = actions.onAddNote,
                onAddMark = actions.onAddMark,
                onDeleteNote = actions.onDeleteNote,
                modifier = Modifier.padding(horizontal = StenografDimens.ScreenPadding),
            )
            TextButton(onClick = { showCancelRecording = true }, modifier = Modifier.align(Alignment.End)) {
                Text("Отменить запись", color = MaterialTheme.colorScheme.error)
            }
        }

        AnimatedContent(
            targetState = state.content,
            contentKey = { it::class },
            transitionSpec = {
                // Fade through: старое содержимое уходит быстрее, чем появляется новое.
                fadeIn(tween(210, delayMillis = 90, easing = StenografMotion.Emphasized)) togetherWith
                    fadeOut(tween(90))
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
            label = "homeContent",
        ) { content ->
            when (content) {
                is HomeContent.Overview -> HomeOverview(
                    content = content,
                    contentPadding = contentPadding,
                    onShowModelsDetails = { showModelsDetails = true },
                    onExpandJob = actions.onExpandJob,
                    onOpenRecording = actions.onOpenRecording,
                    onOpenAllRecordings = actions.onOpenAllRecordings,
                    onDeleteRecording = actions.onDeleteRecording,
                    onRenameRecording = actions.onRenameRecording,
                    onCorrectText = actions.onCorrectText,
                )
                is HomeContent.ProcessingDetail -> ProcessingDetail(
                    job = content.job,
                    contentPadding = contentPadding,
                    onCancel = { actions.onCancelJob(content.job.recordingId) },
                    onCollapse = actions.onCollapseJob,
                    onRetry = { actions.onRetryJob(content.job.recordingId) },
                    onSkipStage = { stage -> actions.onSkipStage(content.job.recordingId, stage) },
                    onOpenResult = { actions.onOpenRecording(content.job.recordingId) },
                )
                is HomeContent.LiveFeed -> LiveFeed(
                    lines = content.lines,
                    contentPadding = contentPadding,
                    jumpButtonBottomPadding = overlayHeight + 12.dp,
                    status = content.status,
                    recognizing = content.recognizing,
                )
                is HomeContent.RecordingWithoutLive -> RecordingPanel(
                    paused = recording?.paused == true,
                    signal = signal,
                    contentPadding = contentPadding,
                )
            }
        }
    }

    if (showCancelRecording && recording != null) {
        AlertDialog(
            onDismissRequest = { showCancelRecording = false },
            title = { Text("Отменить запись?") },
            text = { Text("Записанное аудио и Live-расшифровка будут удалены. Это действие нельзя отменить.") },
            dismissButton = { TextButton(onClick = { showCancelRecording = false }) { Text("Продолжить запись") } },
            confirmButton = { TextButton(onClick = {
                showCancelRecording = false
                actions.onCancelRecording()
            }) { Text("Удалить запись", color = MaterialTheme.colorScheme.error) } },
        )
    }
    if (showModelsDetails) {
        AlertDialog(
            onDismissRequest = { showModelsDetails = false },
            icon = { Icon(AppIcons.Warning, contentDescription = null) },
            title = { Text(stringResource(R.string.home_models_unavailable)) },
            text = {
                Text(
                    state.modelsIssue?.details ?: stringResource(R.string.home_models_details_fallback),
                    style = StenografType.body,
                )
            },
            confirmButton = {
                TextButton(onClick = { showModelsDetails = false }) { Text(stringResource(R.string.home_close)) }
            },
        )
    }
}


@Composable
private fun HomeHeader(onOpenSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(start = StenografDimens.ScreenPadding, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.home_title),
            style = StenografType.screenTitle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        IconButton(onClick = onOpenSettings) {
            Icon(
                AppIcons.Tune,
                contentDescription = stringResource(R.string.home_settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Два равных действия высотой 48: Live-текст слева, справа импорт или пауза. */
@Composable
private fun ActionRow(
    liveTextEnabled: Boolean,
    recording: Boolean,
    paused: Boolean,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().height(StenografDimens.MinTouch),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val cell = Modifier.weight(1f).fillMaxHeight()
        val liveState = stringResource(if (liveTextEnabled) R.string.home_live_text_on else R.string.home_live_text_off)
        val liveSemantics = Modifier.semantics {
            role = Role.Switch
            stateDescription = liveState
        }
        if (liveTextEnabled) {
            FilledTonalButton(
                onClick = actions.onToggleLiveText,
                shape = StenografShapes.Pill,
                modifier = cell.then(liveSemantics),
            ) {
                ButtonContent(AppIcons.Check, stringResource(R.string.home_live_text))
            }
        } else {
            OutlinedButton(
                onClick = actions.onToggleLiveText,
                shape = StenografShapes.Pill,
                modifier = cell.then(liveSemantics),
            ) {
                Text(
                    stringResource(R.string.home_live_text),
                    style = StenografType.label,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        when {
            !recording -> OutlinedButton(onClick = actions.onUploadFile, shape = StenografShapes.Pill, modifier = cell) {
                ButtonContent(AppIcons.Upload, stringResource(R.string.home_upload_file))
            }
            paused -> OutlinedButton(onClick = actions.onResume, shape = StenografShapes.Pill, modifier = cell) {
                ButtonContent(AppIcons.Play, stringResource(R.string.home_resume))
            }
            else -> OutlinedButton(onClick = actions.onPause, shape = StenografShapes.Pill, modifier = cell) {
                ButtonContent(AppIcons.Pause, stringResource(R.string.home_pause))
            }
        }
    }
}

@Composable
private fun ButtonContent(icon: ImageVector, label: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(label, style = StenografType.label, maxLines = 1)
}
