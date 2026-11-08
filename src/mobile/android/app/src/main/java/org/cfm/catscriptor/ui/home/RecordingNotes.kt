package org.cfm.catscriptor.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.cfm.catscriptor.domain.app.RecordingNote
import org.cfm.catscriptor.ui.components.AppIcons
import org.cfm.catscriptor.ui.components.formatTimecode
import org.cfm.catscriptor.ui.theme.StenografShapes
import org.cfm.catscriptor.ui.theme.StenografType

/** Единая компактная панель пометок для обоих режимов живой записи. */
@Composable
internal fun RecordingNotes(
    notes: List<RecordingNote>,
    signal: () -> org.cfm.catscriptor.viewmodel.home.RecorderSignal?,
    onAddNote: (String, Long?) -> Unit,
    onAddMark: () -> Unit,
    onDeleteNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dialogOpen by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var notePositionMs by rememberSaveable { mutableStateOf(0L) }
    Column(modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = {
                text = ""
                notePositionMs = signal()?.elapsedMs ?: 0L
                dialogOpen = true
            }) {
                Icon(AppIcons.Bookmark, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Пометка")
            }
            OutlinedButton(onClick = onAddMark) { Text("Метка времени") }
            if (notes.isNotEmpty()) Text("${notes.size}", style = StenografType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (notes.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                items(notes, key = { it.id }) { note ->
                    Surface(shape = StenografShapes.Card, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(
                            Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(formatTimecode(note.positionMs), style = StenografType.timecode, color = MaterialTheme.colorScheme.primary)
                            if (note.text.isNotBlank()) {
                                Text(" · ${note.text}", style = StenografType.meta, maxLines = 1, modifier = Modifier.widthIn(max = 180.dp))
                            }
                            IconButton(
                                onClick = { onDeleteNote(note.id) },
                                modifier = Modifier.semantics { contentDescription = "Удалить пометку ${formatTimecode(note.positionMs)}" },
                            ) { Icon(AppIcons.Close, contentDescription = null) }
                        }
                    }
                }
            }
        }
    }
    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text("Добавить пометку") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Время ${formatTimecode(notePositionMs)}", style = StenografType.timecode, color = MaterialTheme.colorScheme.primary)
                    Text("Пустой текст сохранится как отметка времени.", style = StenografType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(2000) },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp),
                        label = { Text("Текст пометки") },
                        supportingText = { Text("До 2000 символов") },
                        maxLines = 4,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { dialogOpen = false }) { Text("Отмена") } },
            confirmButton = {
                Row {
                    TextButton(onClick = { dialogOpen = false; onAddNote("", notePositionMs) }) { Text("Только время") }
                    TextButton(onClick = { dialogOpen = false; onAddNote(text.trim(), notePositionMs) }) { Text("Сохранить") }
                }
            },
        )
    }
}
