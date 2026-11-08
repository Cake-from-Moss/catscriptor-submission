package org.cfm.catscriptor.ui.recordings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.cfm.catscriptor.R
import org.cfm.catscriptor.domain.app.RecordingFilter
import org.cfm.catscriptor.domain.app.RecordingQuery
import org.cfm.catscriptor.domain.app.RecordingSource
import org.cfm.catscriptor.domain.app.RecordingStatus
import org.cfm.catscriptor.domain.app.RecordingSummary
import org.cfm.catscriptor.ui.components.AppIcons
import org.cfm.catscriptor.ui.components.SwipeToRevealDelete
import org.cfm.catscriptor.ui.components.formatTimecode
import org.cfm.catscriptor.ui.theme.StenografDimens
import org.cfm.catscriptor.ui.theme.StenografShapes
import org.cfm.catscriptor.ui.theme.StenografType
import org.cfm.catscriptor.viewmodel.recordings.RecordingGroupKey
import org.cfm.catscriptor.viewmodel.recordings.RecordingsUiState

/** Действия строки списка; экран сам ничего не решает, только передаёт нажатия. */
class RecordingRowActions(
    val onOpen: (RecordingSummary) -> Unit,
    val onRename: (RecordingSummary) -> Unit,
    val onCorrect: (RecordingSummary) -> Unit = {},
    val onExport: (RecordingSummary) -> Unit,
    val onDelete: (RecordingSummary) -> Unit,
)

@Composable
fun RecordingsScreen(
    state: RecordingsUiState,
    query: RecordingQuery,
    onSearchChange: (String) -> Unit,
    onFilterChange: (RecordingFilter) -> Unit,
    onGoToRecord: () -> Unit,
    onOpenSettings: () -> Unit,
    rowActions: RecordingRowActions,
    modifier: Modifier = Modifier,
) {
    val statusBars = WindowInsets.statusBars.asPaddingValues()
    val navigationBars = WindowInsets.navigationBars.asPaddingValues()
    // Список прокручивается под плавающей навигацией: отступ = панель + её поле + воздух.
    val bottomPadding = navigationBars.calculateBottomPadding() +
        StenografDimens.BottomBarHeight + StenografDimens.BottomBarMargin + StenografDimens.ScreenPadding

    LazyColumn(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(top = statusBars.calculateTopPadding(), bottom = bottomPadding),
    ) {
        item(key = "title", contentType = "title") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.recordings_title),
                    style = StenografType.screenTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = StenografDimens.ScreenPadding)
                        .padding(top = 16.dp, bottom = 16.dp)
                        .semantics { heading() },
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(AppIcons.Tune, contentDescription = stringResource(R.string.home_settings))
                }
                Spacer(Modifier.width(4.dp))
            }
        }
        item(key = "search", contentType = "search") {
            SearchField(
                text = query.text,
                onTextChange = onSearchChange,
                modifier = Modifier.padding(horizontal = StenografDimens.ScreenPadding),
            )
        }
        item(key = "filters", contentType = "filters") {
            FilterRow(
                selected = query.filter,
                onSelect = onFilterChange,
                modifier = Modifier.padding(horizontal = StenografDimens.ScreenPadding, vertical = 12.dp),
            )
        }

        when {
            state.isEmpty && state.isFiltered -> item(key = "not-found", contentType = "empty") {
                EmptyMessage(
                    title = if (query.text.isNotBlank()) {
                        stringResource(R.string.recordings_not_found, query.text.trim())
                    } else {
                        stringResource(R.string.recordings_empty_title)
                    },
                )
            }
            state.isEmpty -> item(key = "empty", contentType = "empty") {
                EmptyMessage(
                    title = stringResource(R.string.recordings_empty_title),
                    action = stringResource(R.string.recordings_empty_action),
                    onAction = onGoToRecord,
                )
            }
            else -> state.groups.forEach { group ->
                item(key = "group-${group.key}", contentType = "header") {
                    Text(
                        groupLabel(group.key),
                        style = StenografType.label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = StenografDimens.ScreenPadding)
                            .padding(top = 12.dp, bottom = 4.dp)
                            .semantics { heading() },
                    )
                }
                items(group.items, key = { it.id }, contentType = { "row" }) { summary ->
                    SwipeToRevealDelete(onDelete = { rowActions.onDelete(summary) }, onRename = { rowActions.onRename(summary) }, modifier = Modifier.animateItem()) {
                    RecordingRow(
                        summary = summary,
                        groupKey = group.key,
                        actions = rowActions,
                    )
                    }
                }
            }
        }
    }
}

/** Поиск 56 dp с радиусом 28, как в макете; поле M3 с обводкой здесь не подходит. */
@Composable
private fun SearchField(text: String, onTextChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    Surface(
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = StenografShapes.Pill,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
            Icon(AppIcons.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) {
                    Text(
                        stringResource(R.string.recordings_search_hint),
                        style = StenografType.body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    singleLine = true,
                    textStyle = StenografType.body.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AnimatedVisibility(visible = text.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                IconButton(onClick = { onTextChange("") }) {
                    Icon(
                        AppIcons.Close,
                        contentDescription = stringResource(R.string.recordings_search_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterRow(selected: RecordingFilter, onSelect: (RecordingFilter) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(StenografDimens.InnerGap)) {
        RecordingFilter.entries.forEach { filter ->
            val isSelected = filter == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(filter) },
                label = {
                    Text(
                        stringResource(
                            when (filter) {
                                RecordingFilter.ALL -> R.string.recordings_filter_all
                                RecordingFilter.READY -> R.string.recordings_filter_ready
                                RecordingFilter.PROCESSING -> R.string.recordings_filter_processing
                            },
                        ),
                    )
                },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = MaterialTheme.colorScheme.outline,
                ),
            )
        }
    }
}

@Composable
private fun RecordingRow(
    summary: RecordingSummary,
    groupKey: RecordingGroupKey,
    actions: RecordingRowActions,
    modifier: Modifier = Modifier,
) {
    val processing = summary.status as? RecordingStatus.Processing
    val highlighted = summary.status != RecordingStatus.Ready && summary.status != RecordingStatus.AsrReady && summary.status !is RecordingStatus.Failed
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { actions.onOpen(summary) }
            .heightIn(min = 72.dp)
            .padding(start = StenografDimens.ScreenPadding, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SourceIcon(source = summary.source, highlighted = highlighted)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    summary.title,
                    style = StenografType.body,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    recordingTimeLabel(summary.createdAtEpochMs, groupKey),
                    style = StenografType.timecode,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                metaLine(summary),
                style = StenografType.timecode.copy(fontSize = StenografType.meta.fontSize),
                color = if (summary.status is RecordingStatus.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (processing != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { processing.fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
        RowMenu(summary = summary, actions = actions)
    }
}

@Composable
private fun SourceIcon(source: RecordingSource, highlighted: Boolean) {
    val container = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (source == RecordingSource.MICROPHONE) AppIcons.Mic else AppIcons.AudioFile,
            contentDescription = stringResource(
                if (source == RecordingSource.MICROPHONE) R.string.recordings_source_mic else R.string.recordings_source_file,
            ),
            tint = content,
        )
    }
}

@Composable
private fun metaLine(summary: RecordingSummary): String = when (val status = summary.status) {
    is RecordingStatus.Processing -> stringResource(
        R.string.recordings_processing_meta,
        (status.fraction.coerceIn(0f, 1f) * 100).toInt(),
        stageLabel(status.stage),
    )
    RecordingStatus.Queued -> stringResource(R.string.recordings_queued_meta)
    is RecordingStatus.Failed -> stringResource(R.string.recordings_failed_meta, stageLabel(status.stage))
    RecordingStatus.AsrReady -> stringResource(R.string.recordings_asr_ready_meta, formatTimecode(summary.durationMs))
    RecordingStatus.Ready -> {
        val duration = formatTimecode(summary.durationMs)
        summary.speakerCount?.let { stringResource(R.string.meta_separator, duration, speakersCountLabel(it)) }
            ?: duration
    }
}

@Composable
private fun RowMenu(summary: RecordingSummary, actions: RecordingRowActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                AppIcons.More,
                contentDescription = stringResource(R.string.recordings_more, summary.title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.recordings_menu_rename)) },
                leadingIcon = { Icon(AppIcons.Edit, contentDescription = null) },
                onClick = { expanded = false; actions.onRename(summary) },
            )
            if (summary.status == RecordingStatus.Ready || summary.status == RecordingStatus.AsrReady) {
                DropdownMenuItem(
                    text = {
                        Text(stringResource(if (summary.textCorrected) R.string.recordings_menu_correct_again else R.string.recordings_menu_correct))
                    },
                    leadingIcon = { Icon(AppIcons.Edit, contentDescription = null) },
                    onClick = { expanded = false; actions.onCorrect(summary) },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.recordings_menu_export)) },
                leadingIcon = { Icon(AppIcons.Share, contentDescription = null) },
                // У неготовой записи нет расшифровки, экспортировать нечего.
                enabled = summary.status == RecordingStatus.Ready,
                onClick = { expanded = false; actions.onExport(summary) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.recordings_menu_delete)) },
                leadingIcon = { Icon(AppIcons.Delete, contentDescription = null) },
                onClick = { expanded = false; actions.onDelete(summary) },
            )
        }
    }
}

@Composable
private fun EmptyMessage(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = StenografType.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAction, shape = CircleShape) { Text(action) }
        }
    }
}
