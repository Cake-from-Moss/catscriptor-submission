package org.cfm.catscriptor.ui.recordings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.cfm.catscriptor.R

/**
 * Диалог M3 с одним полем: переименование записи и спикера, правка текста реплики.
 * Поле сразу получает фокус, а текст выделен целиком, чтобы его можно было заменить одним вводом.
 */
@Composable
internal fun TextInputDialog(
    title: String,
    label: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    supportingText: String? = null,
    singleLine: Boolean = true,
    allowEmpty: Boolean = false,
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialValue, selection = TextRange(0, initialValue.length)))
    }
    val focusRequester = remember { FocusRequester() }
    val canConfirm = allowEmpty || value.text.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (supportingText != null) {
                    Text(
                        supportingText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = singleLine,
                    minLines = if (singleLine) 1 else 3,
                    maxLines = if (singleLine) 1 else 8,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (canConfirm) onConfirm(value.text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }, enabled = canConfirm) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )

    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
}
