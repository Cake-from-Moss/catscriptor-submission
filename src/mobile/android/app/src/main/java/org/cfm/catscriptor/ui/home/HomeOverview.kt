package org.cfm.catscriptor.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.cfm.catscriptor.ui.components.SwipeToRevealDelete
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.cfm.catscriptor.R
import org.cfm.catscriptor.domain.app.ProcessingJob
import org.cfm.catscriptor.domain.app.RecordingSource
import org.cfm.catscriptor.domain.app.RecordingStatus
import org.cfm.catscriptor.domain.app.RecordingSummary
import org.cfm.catscriptor.domain.app.StageStatus
import org.cfm.catscriptor.ui.components.AppIcons
import org.cfm.catscriptor.ui.components.formatTimecode
import org.cfm.catscriptor.ui.theme.StenografShapes
import org.cfm.catscriptor.ui.theme.StenografType
import org.cfm.catscriptor.viewmodel.home.HomeContent

/** Ожидание: проблема с моделями, карточки «В обработке», «Недавние» или пустое состояние. */
@Composable
internal fun HomeOverview(
    content: HomeContent.Overview,
    contentPadding: PaddingValues,
    onShowModelsDetails: () -> Unit,
    onExpandJob: (String) -> Unit,
    onOpenRecording: (String) -> Unit,
    onOpenAllRecordings: () -> Unit,
    onDeleteRecording: (String) -> Unit = {},
    onRenameRecording: (RecordingSummary) -> Unit = {},
    onCorrectText: (RecordingSummary) -> Unit = {},
) {
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
        if (content.showModelsIssue) {
            item(key = "models") {
                ModelsIssueCard(onShowDetails = onShowModelsDetails, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (content.jobs.isNotEmpty()) {
            item(key = "jobsHeader") { SectionLabel(stringResource(R.string.home_in_processing)) }
            items(content.jobs, key = { "job:${it.recordingId}" }) { job ->
                SwipeToRevealDelete(onDelete = { onDeleteRecording(job.recordingId) }, modifier = Modifier.animateItem()) {
                CompactJobCard(
                    job = job,
                    onClick = { onExpandJob(job.recordingId) },
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                }
            }
        }
        if (content.recents.isNotEmpty()) {
            item(key = "recentsHeader") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.home_recents),
                        style = StenografType.label,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    TextButton(onClick = onOpenAllRecordings) {
                        Text(stringResource(R.string.home_all_recordings), style = StenografType.label)
                    }
                }
            }
            item(key = "recents") {
                Column(
                    Modifier.fillMaxWidth().clip(StenografShapes.Card),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    content.recents.forEach { summary ->
                        key(summary.id) {
                        SwipeToRevealDelete(onDelete = { onDeleteRecording(summary.id) }, onRename = { onRenameRecording(summary) }) {
                        RecentRow(
                            summary = summary,
                            onClick = { onOpenRecording(summary.id) },
                            onRename = { onRenameRecording(summary) },
                            onCorrect = { onCorrectText(summary) },
                            onDelete = { onDeleteRecording(summary.id) },
                        )
                        }
                        }
                    }
                }
            }
        }
        if (content.showEmptyState) {
            item(key = "empty") { EmptyState() }
        }
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = StenografType.label,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .padding(top = 16.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/** Компактная карточка задачи: название, %, этап и оставшееся время. Нажатие раскрывает этапы. */
@Composable
private fun CompactJobCard(job: ProcessingJob, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val failed = job.stages.firstOrNull { it.status is StageStatus.Failed }
    val running = job.stages.firstOrNull { it.status is StageStatus.Running }
    val (status, isError) = when {
        failed != null -> stringResource(R.string.home_failed_at, stringResource(failed.stage.titleRes())) to true
        running != null -> joinMeta(listOf(stringResource(running.stage.titleRes()), remainingLabel(job))) to false
        else -> stringResource(R.string.home_queued) to false
    }
    val progress by animateFloatAsState(job.overallFraction.coerceIn(0f, 1f), tween(PROGRESS_ANIMATION_MS), label = "jobProgress")
    Surface(
        onClick = onClick,
        shape = StenografShapes.Card,
        color = colors.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    job.title,
                    style = StenografType.body,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.home_percent, percentOf(job.overallFraction)),
                    style = StenografType.timecode,
                    color = colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            ProgressLine(progress = { progress })
            Spacer(Modifier.height(10.dp))
            Text(
                status,
                style = StenografType.meta,
                color = if (isError) colors.error else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Линейный прогресс 6 dp без зазора и точки остановки из M3 Expressive. */
@Composable
internal fun ProgressLine(progress: () -> Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = progress,
        modifier = modifier.fillMaxWidth().height(6.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

@Composable
private fun RecentRow(summary: RecordingSummary, onClick: () -> Unit, onRename: () -> Unit, onCorrect: () -> Unit, onDelete: () -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val preview = summary.previewText?.takeIf { it.isNotBlank() }?.let { text ->
        val speaker = summary.previewSpeakerName
            ?: summary.previewSpeakerId?.let { speakerLabel(it) }
        if (speaker != null) stringResource(R.string.home_preview_line, speaker, text) else text
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerLow)
            .clickable(onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(colors.primaryContainer)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            val speakers = summary.speakerCount
            if (speakers != null) {
                Text(speakers.toString(), style = StenografType.label, color = colors.onPrimaryContainer)
            } else {
                Icon(
                    if (summary.source == RecordingSource.FILE) AppIcons.AudioFile else AppIcons.Mic,
                    contentDescription = null,
                    tint = colors.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                summary.title,
                style = StenografType.body,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.status == RecordingStatus.AsrReady) {
                Text(
                    stringResource(R.string.recordings_asr_ready_meta, formatTimecode(summary.durationMs)),
                    style = StenografType.timecode,
                    color = colors.primary,
                    maxLines = 1,
                )
            }
            if (preview != null) {
                Text(
                    preview,
                    style = StenografType.meta,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(formatTimecode(summary.durationMs), style = StenografType.timecode, color = colors.onSurfaceVariant)
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(AppIcons.More, contentDescription = stringResource(R.string.recordings_more, summary.title), tint = colors.onSurfaceVariant)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.recordings_menu_rename)) },
                    leadingIcon = { Icon(AppIcons.Edit, contentDescription = null) },
                    onClick = { menuExpanded = false; onRename() },
                )
                if (summary.status == RecordingStatus.Ready || summary.status == RecordingStatus.AsrReady) {
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(if (summary.textCorrected) R.string.recordings_menu_correct_again else R.string.recordings_menu_correct))
                        },
                        leadingIcon = { Icon(AppIcons.Edit, contentDescription = null) },
                        onClick = { menuExpanded = false; onCorrect() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.recordings_menu_delete)) },
                    leadingIcon = { Icon(AppIcons.Delete, contentDescription = null) },
                    onClick = { menuExpanded = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun ModelsIssueCard(onShowDetails: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = StenografShapes.Card, color = colors.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 20.dp, top = 16.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                AppIcons.Warning,
                contentDescription = null,
                tint = colors.error,
                modifier = Modifier.padding(top = 2.dp).size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.home_models_unavailable),
                    style = StenografType.label,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.home_models_unavailable_body),
                    style = StenografType.meta,
                    color = colors.onSurfaceVariant,
                )
                TextButton(onClick = onShowDetails, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.home_details), style = StenografType.label)
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(colors.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Mic, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.home_empty_state),
            style = StenografType.body,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

internal const val PROGRESS_ANIMATION_MS = 300
