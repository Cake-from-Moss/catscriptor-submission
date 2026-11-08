package org.cfm.catscriptor.ui.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.cfm.catscriptor.R
import org.cfm.catscriptor.domain.app.SpeakerInfo
import org.cfm.catscriptor.domain.app.TranscriptLine
import org.cfm.catscriptor.ui.components.AppIcons
import org.cfm.catscriptor.ui.components.formatTimecode
import org.cfm.catscriptor.ui.recordings.speakerName
import org.cfm.catscriptor.ui.theme.StenografShapes
import org.cfm.catscriptor.ui.theme.StenografType
import org.cfm.catscriptor.ui.theme.speakerColors

/**
 * Лист действий по долгому нажатию на реплику. «Переобработать фрагмент» показан выключенным:
 * у конвейера пока нет операции для повторного распознавания отрезка.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineActionsSheet(
    line: TranscriptLine,
    speaker: SpeakerInfo?,
    onEditText: () -> Unit,
    onChangeSpeaker: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = StenografShapes.TopSheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = if (dark) 0.5f else 0.32f),
        dragHandle = { BottomSheetDefaults.DragHandle(width = 32.dp, height = 4.dp) },
    ) {
        Column(Modifier.padding(bottom = bottom + 8.dp)) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp)) {
                Text(
                    stringResource(R.string.transcript_line_actions),
                    style = StenografType.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(speakerColors(line.speakerId).dot))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.meta_separator, speakerName(speaker, line.speakerId), formatTimecode(line.startMs)),
                        style = StenografType.timecode,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    line.text,
                    style = StenografType.body,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ActionItem(AppIcons.Edit, stringResource(R.string.transcript_action_edit), onClick = onEditText)
            ActionItem(AppIcons.Person, stringResource(R.string.transcript_action_change_speaker), onClick = onChangeSpeaker)
            ActionItem(
                icon = AppIcons.Refresh,
                title = stringResource(R.string.transcript_action_reprocess),
                supporting = stringResource(R.string.transcript_action_reprocess_unavailable),
                onClick = null,
            )
            ActionItem(AppIcons.Copy, stringResource(R.string.transcript_action_copy), onClick = onCopy)
        }
    }
}

/** [onClick] = null делает пункт выключенным: он виден, но не нажимается. */
@Composable
private fun ActionItem(icon: ImageVector, title: String, supporting: String? = null, onClick: (() -> Unit)?) {
    val enabled = onClick != null
    val contentAlpha = if (enabled) 1f else 0.38f
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    )
}

@Composable
fun ChangeSpeakerDialog(
    line: TranscriptLine,
    speakers: List<SpeakerInfo>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by rememberSaveable(line.id) { mutableStateOf(line.speakerId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.transcript_change_speaker_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                speakers.forEach { speaker ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(CircleShape)
                            .selectable(
                                selected = speaker.id == selected,
                                role = Role.RadioButton,
                                onClick = { selected = speaker.id },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = speaker.id == selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
                        Box(Modifier.size(10.dp).clip(CircleShape).background(speakerColors(speaker.id).dot))
                        Spacer(Modifier.width(8.dp))
                        Text(speakerName(speaker, speaker.id), style = StenografType.body)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
