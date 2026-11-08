package org.cfm.catscriptor.ui.export

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.cfm.catscriptor.R
import org.cfm.catscriptor.app.AppGraph
import org.cfm.catscriptor.domain.app.ExportFormat
import org.cfm.catscriptor.domain.app.SharePayload
import org.cfm.catscriptor.domain.app.TranscriptRendering
import org.cfm.catscriptor.ui.components.AppIcons
import org.cfm.catscriptor.ui.components.formatTimecode
import org.cfm.catscriptor.ui.navigation.AppSnackbar
import org.cfm.catscriptor.ui.navigation.LocalAppSnackbar
import org.cfm.catscriptor.ui.recordings.speakersCountLabel
import org.cfm.catscriptor.ui.theme.StenografDimens
import org.cfm.catscriptor.ui.theme.StenografShapes
import org.cfm.catscriptor.ui.theme.StenografType
import org.cfm.catscriptor.viewmodel.export.ExportEvent
import org.cfm.catscriptor.viewmodel.export.ExportUiState
import org.cfm.catscriptor.viewmodel.export.ExportViewModel

/**
 * Лист экспорта поверх расшифровки или списка записей. Route владеет системными окнами:
 * выбором папки (CreateDocument), share sheet и открытием сохранённого файла.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheetRoute(graph: AppGraph, recordingId: String, onDismiss: () -> Unit) {
    val viewModel = viewModel(key = "export-$recordingId") {
        ExportViewModel(recordingId, graph.export, graph.recordings, graph.settings)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = LocalAppSnackbar.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val texts = ExportTexts(
        savedTemplate = stringResource(R.string.export_saved),
        open = stringResource(R.string.export_open),
        saveFailed = stringResource(R.string.export_save_failed),
        shareFailed = stringResource(R.string.export_share_failed),
        noApp = stringResource(R.string.export_no_app),
        chooser = stringResource(R.string.export_chooser),
    )

    // rememberLauncherForActivityResult регистрирует контракт один раз, поэтому MIME нельзя
    // зашивать в сам контракт: иначе после смены формата/опции аудио в диалог уходит старый тип
    // и система дописывает лишнее расширение (.zip.json). MIME едет во входных данных.
    val createDocument = rememberLauncherForActivityResult(CreateDocumentWithType) { uri ->
        viewModel.onSaveTarget(uri?.toString())
    }

    val closeSheet: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
    }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is ExportEvent.Share -> context.startShare(event.payload, texts, snackbar)
                is ExportEvent.Saved -> {
                    snackbar.show(
                        message = texts.savedTemplate.format(event.displayName),
                        actionLabel = texts.open,
                        onAction = { context.openDocument(event.documentUri, event.mimeType, texts, snackbar) },
                    )
                    closeSheet()
                }
                ExportEvent.SaveFailed -> snackbar.show(texts.saveFailed)
                ExportEvent.ShareFailed -> snackbar.show(texts.shareFailed)
            }
        }
    }

    // Затемнение 32 % в светлой теме и 50 % в тёмной; тёмную определяем по фону, так как
    // флаг темы спрятан внутри StenografTheme.
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = StenografShapes.TopSheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = if (dark) 0.5f else 0.32f),
        dragHandle = { BottomSheetDefaults.DragHandle(width = 32.dp, height = 4.dp) },
    ) {
        ExportSheetContent(
            state = state,
            onFormatChange = viewModel::onFormatChange,
            onSpeakerNamesChange = viewModel::onSpeakerNamesChange,
            onIncludeAudioChange = viewModel::onIncludeAudioChange,
            onAudioOnlyChange = viewModel::onAudioOnlyChange,
            onShare = viewModel::onShare,
            onSave = { createDocument.launch(viewModel.suggestedFileName() to viewModel.mimeType()) },
        )
    }
}

/** Вход: имя файла и MIME-тип. */
private object CreateDocumentWithType : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second)
            .putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent.takeIf { resultCode == Activity.RESULT_OK }?.data
}

/** Строки берутся в композиции заранее: обработчики событий работают вне неё. */
private class ExportTexts(
    val savedTemplate: String,
    val open: String,
    val saveFailed: String,
    val shareFailed: String,
    val noApp: String,
    val chooser: String,
)

private fun Context.startShare(payload: SharePayload, texts: ExportTexts, snackbar: AppSnackbar) {
    val uris = (listOf(payload.contentUri) + payload.extraContentUris).map { it.toUri() }
    // Расшифровка с аудио уходит одним ACTION_SEND_MULTIPLE; ClipData нужен, чтобы разрешение
    // на чтение получили все URI, а не только первый.
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
        .setType(payload.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    try {
        startActivity(Intent.createChooser(send, texts.chooser))
    } catch (_: ActivityNotFoundException) {
        snackbar.show(texts.noApp)
    }
}

private fun Context.openDocument(documentUri: String, mimeType: String, texts: ExportTexts, snackbar: AppSnackbar) {
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(documentUri.toUri(), mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(view)
    } catch (_: ActivityNotFoundException) {
        snackbar.show(texts.noApp)
    }
}

@Composable
fun ExportSheetContent(
    state: ExportUiState,
    onFormatChange: (ExportFormat) -> Unit,
    onSpeakerNamesChange: (Boolean) -> Unit,
    onIncludeAudioChange: (Boolean) -> Unit,
    onAudioOnlyChange: (Boolean) -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = bottomInset + 16.dp),
    ) {
        Text(stringResource(R.string.export_title), style = StenografType.sheetTitle, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle(state),
            style = StenografType.meta.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (!state.options.audioOnly) {
            SectionLabel(stringResource(R.string.export_format))
            FormatSelector(selected = state.options.format, onSelect = onFormatChange)

            SectionLabel(stringResource(R.string.export_preview))
            PreviewBlock(state.preview)
        }

        Spacer(Modifier.height(12.dp))
        SwitchRow(
            title = stringResource(R.string.export_speaker_names),
            supporting = if (state.speakerNamesApplicable) null else stringResource(R.string.export_speaker_names_json),
            checked = state.options.useSpeakerNames,
            enabled = state.speakerNamesApplicable,
            onCheckedChange = onSpeakerNamesChange,
        )
        SwitchRow(
            title = stringResource(R.string.export_with_audio),
            supporting = null,
            checked = state.options.includeAudio && !state.options.audioOnly,
            enabled = !state.options.audioOnly,
            onCheckedChange = onIncludeAudioChange,
        )
        SwitchRow(
            title = stringResource(R.string.export_audio_only),
            supporting = null,
            checked = state.options.audioOnly,
            enabled = true,
            onCheckedChange = onAudioOnlyChange,
        )

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(StenografDimens.InnerGap)) {
            OutlinedButton(
                onClick = onShare,
                enabled = state.actionsEnabled,
                shape = CircleShape,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(AppIcons.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.export_share))
            }
            Button(
                onClick = onSave,
                enabled = state.actionsEnabled,
                shape = CircleShape,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(AppIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.export_save))
            }
        }
    }
}

@Composable
private fun subtitle(state: ExportUiState): String {
    val duration = formatTimecode(state.durationMs)
    val withDuration = stringResource(R.string.meta_separator, state.title, duration)
    return if (state.speakerCount > 0) {
        stringResource(R.string.meta_separator, withDuration, speakersCountLabel(state.speakerCount))
    } else {
        withDuration
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = StenografType.label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun FormatSelector(selected: ExportFormat, onSelect: (ExportFormat) -> Unit) {
    val formats = ExportFormat.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        formats.forEachIndexed { index, format ->
            SegmentedButton(
                selected = format == selected,
                onClick = { onSelect(format) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = formats.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                modifier = Modifier.height(48.dp),
            ) {
                Text(
                    stringResource(
                        when (format) {
                            ExportFormat.TXT -> R.string.export_format_txt
                            ExportFormat.JSON -> R.string.export_format_json
                            ExportFormat.SRT -> R.string.export_format_srt
                        },
                    ),
                )
            }
        }
    }
}

/**
 * Высота блока рассчитана на полные [TranscriptRendering.PREVIEW_LINES] строк плюс «…»,
 * чтобы лист не прыгал при смене формата. Каждая строка обрезается многоточием, а не переносится:
 * так видна структура формата, как в макете.
 */
@Composable
private fun PreviewBlock(preview: String?) {
    val lineHeight = with(LocalDensity.current) { StenografType.mono.lineHeight.toDp() }
    val blockHeight = lineHeight * (TranscriptRendering.PREVIEW_LINES + 1) + 32.dp
    AnimatedContent(
        targetState = preview,
        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
        contentAlignment = Alignment.TopStart,
        label = "preview",
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = blockHeight)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, StenografShapes.JsonPreview)
            .padding(16.dp),
    ) { text ->
        Column(Modifier.fillMaxWidth()) {
            text.orEmpty().lines().forEach { line ->
                Text(
                    line,
                    style = StenografType.mono,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    supporting: String?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                title,
                style = StenografType.body,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            if (supporting != null) {
                Text(supporting, style = StenografType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(16.dp))
        // Нажатие обрабатывает вся строка, поэтому у самого переключателя обработчика нет.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
