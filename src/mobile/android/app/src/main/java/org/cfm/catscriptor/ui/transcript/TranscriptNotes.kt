package org.cfm.catscriptor.ui.transcript

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.cfm.catscriptor.domain.app.RecordingNote
import org.cfm.catscriptor.ui.components.AppIcons
import org.cfm.catscriptor.ui.components.formatTimecode
import org.cfm.catscriptor.ui.theme.StenografShapes
import org.cfm.catscriptor.ui.theme.StenografType

/** Пометки живут отдельно от строк расшифровки и экспорта JSON. */
@Composable
internal fun TranscriptNotes(
    notes: List<RecordingNote>,
    onAdd: () -> Unit,
    onSeek: (Long) -> Unit,
    onEdit: (RecordingNote) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        FilledTonalButton(onClick = onAdd) {
            Icon(AppIcons.Bookmark, contentDescription = null)
            Text("Пометка", modifier = Modifier.padding(start = 6.dp))
        }
        if (notes.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(notes, key = { it.id }) { note ->
                    Surface(
                        onClick = { onSeek(note.positionMs) },
                        shape = StenografShapes.Pill,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(formatTimecode(note.positionMs), style = StenografType.timecode, color = MaterialTheme.colorScheme.primary)
                            Text(
                                note.text.ifBlank { "Метка" },
                                style = StenografType.meta,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 6.dp).widthIn(max = 120.dp),
                            )
                            IconButton(
                                onClick = { onEdit(note) },
                                modifier = Modifier.semantics { contentDescription = "Изменить пометку ${formatTimecode(note.positionMs)}" },
                            ) { Icon(AppIcons.Edit, contentDescription = null) }
                            IconButton(
                                onClick = { onDelete(note.id) },
                                modifier = Modifier.semantics { contentDescription = "Удалить пометку ${formatTimecode(note.positionMs)}" },
                            ) { Icon(AppIcons.Close, contentDescription = null) }
                        }
                    }
                }
            }
        }
    }
}
