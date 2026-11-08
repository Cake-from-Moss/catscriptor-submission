package org.cfm.catscriptor.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.cfm.catscriptor.R
import org.cfm.catscriptor.domain.app.CorrectionLevel
import org.cfm.catscriptor.domain.app.ExportFormat
import org.cfm.catscriptor.domain.app.ModelsStatus
import org.cfm.catscriptor.ui.components.AppIcons
import org.cfm.catscriptor.ui.recordings.speakersCountLabel
import org.cfm.catscriptor.ui.theme.StenografDimens
import org.cfm.catscriptor.ui.theme.StenografType
import org.cfm.catscriptor.viewmodel.settings.SPEAKER_COUNT_OPTIONS
import org.cfm.catscriptor.viewmodel.settings.SUPPORTED_LANGUAGES
import org.cfm.catscriptor.viewmodel.settings.SettingsUiState

class SettingsActions(
    val onBack: () -> Unit,
    val onLanguageChange: (String) -> Unit,
    val onSpeakerCountChange: (Int?) -> Unit,
    val onExportFormatChange: (ExportFormat) -> Unit,
    val onLiveTextChange: (Boolean) -> Unit,
    val onCorrectionLevelChange: (CorrectionLevel) -> Unit = {},
)

@Composable
fun SettingsScreen(state: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(top = top).height(64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions.onBack) {
                Icon(AppIcons.Back, contentDescription = stringResource(R.string.action_back))
            }
            Text(
                stringResource(R.string.settings_title),
                style = StenografType.sheetTitle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 4.dp).semantics { heading() },
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = bottom + 16.dp)) {
            section(R.string.settings_models) { ModelsItem(state.modelsStatus) }
            section(R.string.settings_language) {
                SUPPORTED_LANGUAGES.forEach { language ->
                    RadioItem(
                        title = languageName(language),
                        supporting = languageNote(language),
                        selected = state.settings.language == language,
                        onSelect = { actions.onLanguageChange(language) },
                    )
                }
            }
            section(R.string.settings_speakers) {
                SpeakerCountItem(state.settings.speakerCount, actions.onSpeakerCountChange)
            }
            section(R.string.settings_export) {
                ExportFormat.entries.forEach { format ->
                    RadioItem(
                        title = stringResource(
                            when (format) {
                                ExportFormat.TXT -> R.string.export_format_txt
                                ExportFormat.JSON -> R.string.export_format_json
                                ExportFormat.SRT -> R.string.export_format_srt
                            },
                        ),
                        supporting = null,
                        selected = state.settings.defaultExportFormat == format,
                        onSelect = { actions.onExportFormatChange(format) },
                    )
                }
            }
            section(R.string.settings_live, divider = false) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_live_text)) },
                    supportingContent = { Text(stringResource(R.string.settings_live_text_body)) },
                    trailingContent = { Switch(checked = state.settings.liveTextEnabled, onCheckedChange = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.toggleable(
                        value = state.settings.liveTextEnabled,
                        role = Role.Switch,
                        onValueChange = actions.onLiveTextChange,
                    ),
                )
            }
            section(R.string.settings_correction, divider = false) {
                CorrectionLevelItem(state.settings.correctionLevel, actions.onCorrectionLevelChange)
            }
        }
    }
}

private fun LazyListScope.section(@StringRes title: Int, divider: Boolean = true, content: @Composable () -> Unit) {
    item(key = title) {
        Column {
            Text(
                stringResource(title),
                style = StenografType.label,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = StenografDimens.ScreenPadding)
                    .padding(top = 20.dp, bottom = 4.dp)
                    .semantics { heading() },
            )
            content()
            if (divider) {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 8.dp, start = StenografDimens.ScreenPadding, end = StenografDimens.ScreenPadding),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

@Composable
private fun ModelsItem(status: ModelsStatus) {
    val ready = status is ModelsStatus.Ready
    val details = (status as? ModelsStatus.Unavailable)?.details
    ListItem(
        headlineContent = {
            Text(stringResource(if (ready) R.string.settings_models_ready else R.string.settings_models_unavailable))
        },
        supportingContent = {
            Text(
                details?.takeIf { it.isNotBlank() }
                    ?: stringResource(if (ready) R.string.settings_models_ready_body else R.string.settings_models_unavailable_body),
            )
        },
        leadingContent = {
            Icon(
                if (ready) AppIcons.Check else AppIcons.Warning,
                contentDescription = null,
                tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun RadioItem(title: String, supporting: String?, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    )
}

@Composable
private fun languageName(code: String): String = when (code) {
    "ru" -> stringResource(R.string.settings_language_ru)
    else -> code
}

@Composable
private fun languageNote(code: String): String? = when (code) {
    "ru" -> stringResource(R.string.settings_language_ru_body)
    else -> null
}

/**
 * Слайдер «Авто, 1…5»: крайнее левое положение — авто (null). Во время перетаскивания значение
 * держится локально, а в настройки пишется один раз при отпускании, чтобы не дёргать хранилище.
 */
@Composable
private fun SpeakerCountItem(selected: Int?, onChange: (Int?) -> Unit) {
    val options = SPEAKER_COUNT_OPTIONS
    val savedIndex = options.indexOf(selected).coerceAtLeast(0)
    var dragIndex by remember(savedIndex) { mutableFloatStateOf(savedIndex.toFloat()) }
    val current = options[dragIndex.roundToInt().coerceIn(0, options.lastIndex)]
    Column(Modifier.padding(horizontal = StenografDimens.ScreenPadding, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.settings_speakers_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (current == null) stringResource(R.string.settings_speakers_auto) else current.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val sliderLabel = stringResource(R.string.settings_speakers)
        Slider(
            value = dragIndex,
            onValueChange = { dragIndex = it },
            onValueChangeFinished = { onChange(current) },
            valueRange = 0f..options.lastIndex.toFloat(),
            steps = options.size - 2,
            modifier = Modifier.semantics {
                contentDescription = sliderLabel
                stateDescription = current?.let { it.toString() } ?: "авто"
            },
        )
        Text(
            if (current == null) stringResource(R.string.settings_speakers_auto_body) else speakersCountLabel(current),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Три положения: выкл → быстрая → умная; пояснение раскрывается по значку «?». */
@Composable
private fun CorrectionLevelItem(selected: CorrectionLevel, onSelect: (CorrectionLevel) -> Unit) {
    var helpShown by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = StenografDimens.ScreenPadding, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.settings_correction_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { helpShown = !helpShown }) {
                Icon(AppIcons.Info, contentDescription = stringResource(R.string.settings_correction_help))
            }
        }
        if (helpShown) {
            Text(
                stringResource(R.string.settings_correction_help_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        val levels = CorrectionLevel.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            levels.forEachIndexed { index, level ->
                SegmentedButton(
                    selected = level == selected,
                    onClick = { onSelect(level) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = levels.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        activeContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    modifier = Modifier.height(48.dp),
                ) {
                    Text(
                        stringResource(
                            when (level) {
                                CorrectionLevel.OFF -> R.string.settings_correction_off
                                CorrectionLevel.FAST -> R.string.settings_correction_fast
                                CorrectionLevel.SMART -> R.string.settings_correction_smart
                            },
                        ),
                    )
                }
            }
        }
        Text(
            stringResource(
                when (selected) {
                    CorrectionLevel.OFF -> R.string.settings_correction_off_body
                    CorrectionLevel.FAST -> R.string.settings_correction_fast_body
                    CorrectionLevel.SMART -> R.string.settings_correction_smart_body
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
