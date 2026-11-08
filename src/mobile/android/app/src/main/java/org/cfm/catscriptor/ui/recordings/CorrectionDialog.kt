package org.cfm.catscriptor.ui.recordings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import org.cfm.catscriptor.R
import org.cfm.catscriptor.domain.app.CorrectionLevel

/**
 * Выбор режима правки для одной записи. Общая настройка не меняется: выбранный
 * режим уходит только в [onConfirm]. [CorrectionLevel.OFF] здесь не предлагается,
 * потому что правку запускают явно; по умолчанию берётся режим из настроек.
 */
@Composable
fun CorrectionDialog(
    defaultLevel: CorrectionLevel,
    onConfirm: (CorrectionLevel) -> Unit,
    onDismiss: () -> Unit,
) {
    var level by remember { mutableStateOf(defaultLevel.takeIf { it != CorrectionLevel.OFF } ?: CorrectionLevel.SMART) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.correction_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.correction_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                listOf(CorrectionLevel.FAST, CorrectionLevel.SMART).forEach { option ->
                    ListItem(
                        headlineContent = {
                            Text(
                                stringResource(
                                    if (option == CorrectionLevel.FAST) R.string.settings_correction_fast
                                    else R.string.settings_correction_smart,
                                ),
                            )
                        },
                        supportingContent = {
                            Text(
                                stringResource(
                                    if (option == CorrectionLevel.FAST) R.string.settings_correction_fast_body
                                    else R.string.settings_correction_smart_body,
                                ),
                            )
                        },
                        leadingContent = { RadioButton(selected = level == option, onClick = null) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.selectable(selected = level == option, role = Role.RadioButton) { level = option },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(level) }) { Text(stringResource(R.string.correction_dialog_run)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
