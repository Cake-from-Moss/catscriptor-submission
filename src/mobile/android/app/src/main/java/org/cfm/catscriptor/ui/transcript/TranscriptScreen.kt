package org.cfm.catscriptor.ui.transcript

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.cfm.catscriptor.R
import org.cfm.catscriptor.domain.app.CorrectionLevel
import org.cfm.catscriptor.domain.app.SpeakerInfo
import org.cfm.catscriptor.domain.app.TranscriptDocument
import org.cfm.catscriptor.domain.app.TranscriptLine
import org.cfm.catscriptor.ui.components.AppIcons
import org.cfm.catscriptor.ui.components.formatTimecode
import org.cfm.catscriptor.ui.recordings.CorrectionDialog
import org.cfm.catscriptor.ui.recordings.dateTimeLabel
import org.cfm.catscriptor.ui.recordings.speakerName
import org.cfm.catscriptor.ui.theme.StenografDimens
import org.cfm.catscriptor.ui.theme.StenografMotion
import org.cfm.catscriptor.ui.theme.StenografShapes
import org.cfm.catscriptor.ui.theme.StenografType
import org.cfm.catscriptor.ui.theme.speakerColors
import org.cfm.catscriptor.viewmodel.transcript.TranscriptUiState
import org.cfm.catscriptor.viewmodel.transcript.normalizeForSearch
import org.cfm.catscriptor.viewmodel.transcript.trackSegments

/** Нажатия экрана расшифровки; Route передаёт сюда методы ViewModel. */
class TranscriptActions(
    val onBack: () -> Unit,
    val onShare: () -> Unit,
    val onSearchOpen: () -> Unit,
    val onSearchClose: () -> Unit,
    val onSearchQueryChange: (String) -> Unit,
    val onNextMatch: () -> Unit,
    val onPreviousMatch: () -> Unit,
    val onSpeakerClick: (SpeakerInfo) -> Unit,
    val onLineClick: (TranscriptLine) -> Unit,
    val onLineLongPress: (TranscriptLine) -> Unit,
    val onUserScroll: () -> Unit,
    val onReturnToCurrent: () -> Unit,
    val onSaveNote: (String?, Long, String) -> Unit = { _, _, _ -> },
    val onDeleteNote: (String) -> Unit = {},
    val defaultCorrectionLevel: CorrectionLevel = CorrectionLevel.SMART,
    val onCorrectText: (CorrectionLevel) -> Unit = {},
    val player: PlayerActions,
)

/** Длительность прокрутки к текущей реплике. */
private const val FOLLOW_SCROLL_MS = 300

@Composable
fun TranscriptScreen(
    state: TranscriptUiState,
    searchQuery: String,
    actions: TranscriptActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val document = state.document
    var noteDialogOpen by rememberSaveable { mutableStateOf(false) }
    var editingNoteId by rememberSaveable { mutableStateOf<String?>(null) }
    var noteText by rememberSaveable { mutableStateOf("") }
    var notePositionMs by rememberSaveable { mutableLongStateOf(0L) }
    var correctionConfirm by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        TopBar(
            document = document,
            draft = document?.asrDraft != null,
            searchOpen = state.searchOpen && document?.asrDraft == null,
            searchQuery = searchQuery,
            matchCount = state.search.matchIds.size,
            focusedMatch = state.search.focusedIndex,
            actions = actions,
            onCorrect = { correctionConfirm = true },
        )
        if (correctionConfirm) {
            CorrectionDialog(
                defaultLevel = actions.defaultCorrectionLevel,
                onDismiss = { correctionConfirm = false },
                onConfirm = { level -> correctionConfirm = false; actions.onCorrectText(level) },
            )
        }
        when {
            state.loading -> Spacer(Modifier.weight(1f))
            document == null -> MessageState(
                title = stringResource(R.string.transcript_missing_title),
                body = stringResource(R.string.transcript_missing_body),
                modifier = Modifier.weight(1f),
            )
            else -> {
                val draft = document.asrDraft
                if (draft != null) {
                    DraftNotice(document.asrDraftSpeakers != null)
                } else {
                    SpeakerChips(document.speakers, onClick = actions.onSpeakerClick)
                }
                Box(Modifier.weight(1f)) {
                    if (draft != null) {
                        if (draft.isEmpty()) {
                            MessageState(title = stringResource(R.string.transcript_asr_empty), body = null)
                        } else {
                            AsrDraftLines(draft, document.asrDraftSpeakers)
                        }
                    } else if (document.lines.isEmpty()) {
                        MessageState(title = stringResource(R.string.transcript_empty_body), body = null)
                    } else {
                        TranscriptLines(
                            document = document,
                            state = state,
                            searchQuery = searchQuery,
                            listState = listState,
                            actions = actions,
                        )
                    }
                    // Полное имя: внутри Column иначе выбирается перегрузка ColumnScope.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !state.followPlayback && state.currentLineIndex >= 0,
                        enter = fadeIn(tween(StenografMotion.FADE_IN_MS)) + slideInVertically { it / 2 },
                        exit = fadeOut(tween(StenografMotion.FADE_SHORT_MS)) + slideOutVertically { it / 2 },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                    ) {
                        FilledTonalButton(onClick = actions.onReturnToCurrent, shape = CircleShape) {
                            Text(stringResource(R.string.transcript_to_current))
                        }
                    }
                }
                if (draft == null) {
                    TranscriptNotes(
                        notes = document.notes,
                        onAdd = {
                            editingNoteId = null
                            notePositionMs = state.playback.positionMs
                            noteText = ""
                            noteDialogOpen = true
                        },
                        onSeek = actions.player.onSeek,
                        onEdit = { note ->
                            editingNoteId = note.id
                            notePositionMs = note.positionMs
                            noteText = note.text
                            noteDialogOpen = true
                        },
                        onDelete = actions.onDeleteNote,
                    )
                    val segments = remember(document.lines, state.playback.durationMs) {
                        trackSegments(document.lines, state.playback.durationMs)
                    }
                    PlayerBar(
                        playback = state.playback,
                        segments = segments,
                        followPlayback = state.followPlayback,
                        actions = actions.player,
                    )
                }
            }
        }
    }
    if (noteDialogOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { noteDialogOpen = false },
            title = { Text(if (editingNoteId == null) "Добавить пометку" else "Изменить пометку") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Время ${formatTimecode(notePositionMs)}", style = StenografType.timecode, color = MaterialTheme.colorScheme.primary)
                    Text("Пустой текст сохранится как отметка времени.", style = StenografType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it.take(2000) },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp),
                        label = { Text("Текст пометки") },
                        supportingText = { Text("До 2000 символов") },
                        maxLines = 4,
                    )
                }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { noteDialogOpen = false }) { Text("Отмена") } },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        actions.onSaveNote(editingNoteId, notePositionMs, noteText.trim())
                        noteDialogOpen = false
                    },
                ) { Text("Сохранить") }
            },
        )
    }
}

@Composable
private fun TopBar(
    document: TranscriptDocument?,
    draft: Boolean,
    searchOpen: Boolean,
    searchQuery: String,
    matchCount: Int,
    focusedMatch: Int,
    actions: TranscriptActions,
    onCorrect: () -> Unit,
) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Row(
        Modifier.fillMaxWidth().padding(top = top).height(64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = if (searchOpen) actions.onSearchClose else actions.onBack) {
            Icon(
                AppIcons.Back,
                contentDescription = stringResource(if (searchOpen) R.string.transcript_search_close else R.string.action_back),
            )
        }
        AnimatedContent(
            targetState = searchOpen,
            transitionSpec = { fadeIn(tween(StenografMotion.FADE_IN_MS)) togetherWith fadeOut(tween(StenografMotion.FADE_SHORT_MS)) },
            label = "topBar",
            modifier = Modifier.weight(1f),
        ) { searching ->
            if (searching) {
                SearchRow(searchQuery, matchCount, focusedMatch, actions)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        if (document != null) {
                            Text(
                                document.title,
                                style = StenografType.emphasisTitle,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                stringResource(
                                    R.string.meta_separator,
                                    formatTimecode(document.durationMs),
                                    dateTimeLabel(document.createdAtEpochMs),
                                ),
                                style = StenografType.timecode,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    if (document != null && !draft) {
                        // Подписанная пилюля понятнее безымянного значка карандаша.
                        Surface(
                            onClick = onCorrect,
                            shape = StenografShapes.Pill,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 4.dp).height(32.dp),
                        ) {
                            Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    stringResource(R.string.correction_chip),
                                    style = StenografType.label,
                                    maxLines = 1,
                                )
                            }
                        }
                        IconButton(onClick = actions.onSearchOpen) {
                            Icon(AppIcons.Search, contentDescription = stringResource(R.string.transcript_search))
                        }
                        IconButton(onClick = actions.onShare) {
                            Icon(AppIcons.Share, contentDescription = stringResource(R.string.transcript_share))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DraftNotice(speakersKnown: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = StenografDimens.ScreenPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.transcript_asr_draft),
            style = StenografType.label,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(if (speakersKnown) R.string.transcript_asr_pending_llm else R.string.transcript_asr_pending_stages),
            style = StenografType.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AsrDraftLines(draft: List<org.cfm.catscriptor.domain.pipeline.AsrSegment>, speakers: List<String>?) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(draft, key = { index, segment -> "${segment.time.startMs}-$index" }) { index, segment ->
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    if (speakers != null) {
                        stringResource(R.string.transcript_asr_speaker_interval, speakers[index],
                            formatTimecode(segment.time.startMs), formatTimecode(segment.time.endMs))
                    } else {
                        stringResource(R.string.transcript_asr_interval, formatTimecode(segment.time.startMs), formatTimecode(segment.time.endMs))
                    },
                    style = StenografType.timecode,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(segment.text, style = StenografType.body, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun SearchRow(query: String, matchCount: Int, focusedMatch: Int, actions: TranscriptActions) {
    val focusRequester = remember { FocusRequester() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).padding(start = 4.dp), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    stringResource(R.string.transcript_search_hint),
                    style = StenografType.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = actions.onSearchQueryChange,
                singleLine = true,
                textStyle = StenografType.body.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { actions.onNextMatch() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
        if (query.isNotBlank()) {
            Text(
                if (matchCount == 0) {
                    stringResource(R.string.transcript_search_none)
                } else {
                    stringResource(R.string.transcript_search_counter, focusedMatch + 1, matchCount)
                },
                style = StenografType.timecode,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        IconButton(onClick = actions.onPreviousMatch, enabled = matchCount > 0) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.transcript_search_prev))
        }
        IconButton(onClick = actions.onNextMatch, enabled = matchCount > 0) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.transcript_search_next))
        }
    }
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
}

@Composable
private fun SpeakerChips(speakers: List<SpeakerInfo>, onClick: (SpeakerInfo) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = StenografDimens.ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(StenografDimens.InnerGap),
    ) {
        speakers.forEach { speaker -> SpeakerChip(speaker, onClick = { onClick(speaker) }) }
    }
}

@Composable
private fun SpeakerChip(speaker: SpeakerInfo, onClick: () -> Unit) {
    val name = speakerName(speaker, speaker.id)
    val percent = (speaker.shareFraction * 100).roundToInt()
    val description = stringResource(R.string.transcript_rename_speaker, name)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .semantics { contentDescription = description },
    ) {
        Row(
            Modifier.height(32.dp).padding(start = 10.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpeakerDot(speaker.id, size = 10)
            Spacer(Modifier.width(8.dp))
            Text(name, style = StenografType.label, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.transcript_speaker_share, percent),
                style = StenografType.label.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Icon(AppIcons.Edit, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SpeakerDot(speakerId: String, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(speakerColors(speakerId).dot))
}

@Composable
private fun TranscriptLines(
    document: TranscriptDocument,
    state: TranscriptUiState,
    searchQuery: String,
    listState: LazyListState,
    actions: TranscriptActions,
) {
    val onUserScroll by rememberUpdatedState(actions.onUserScroll)
    // Программная прокрутка (animateScrollBy/ScrollToItem) не проходит через nested scroll,
    // поэтому сюда попадает только жест пользователя — он и выключает «Следовать».
    val userScrollDetector = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) onUserScroll()
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(state.currentLineIndex, state.followPlayback) {
        if (state.followPlayback && state.currentLineIndex >= 0) {
            listState.scrollToUpperThird(state.currentLineIndex)
        }
    }
    val focusedLineId = state.search.focusedLineId
    LaunchedEffect(focusedLineId, state.searchOpen) {
        if (!state.searchOpen || focusedLineId == null) return@LaunchedEffect
        val index = document.lines.indexOfFirst { it.id == focusedLineId }
        if (index >= 0) listState.scrollToUpperThird(index)
    }

    val speakersById = remember(document.speakers) { document.speakers.associateBy { it.id } }
    val needle = normalizeForSearch(searchQuery.trim()).takeIf { state.searchOpen && it.isNotEmpty() }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().nestedScroll(userScrollDetector),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 72.dp),
    ) {
        itemsIndexed(document.lines, key = { _, line -> line.id }, contentType = { _, _ -> "line" }) { index, line ->
            LineItem(
                line = line,
                speakerLabel = speakerName(speakersById[line.speakerId], line.speakerId),
                current = index == state.currentLineIndex,
                needle = needle,
                onClick = { actions.onLineClick(line) },
                onLongClick = { actions.onLineLongPress(line) },
            )
        }
    }
}

/** Ставит реплику в верхнюю треть экрана; видимую докручивает за 300 мс, дальнюю — прыжком с анимацией. */
private suspend fun LazyListState.scrollToUpperThird(index: Int) {
    val target = layoutInfo.viewportSize.height / 6
    val visible = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (visible != null) {
        animateScrollBy((visible.offset - target).toFloat(), tween(FOLLOW_SCROLL_MS, easing = StenografMotion.Emphasized))
    } else {
        animateScrollToItem(index, -target)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LineItem(
    line: TranscriptLine,
    speakerLabel: String,
    current: Boolean,
    needle: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = speakerColors(line.speakerId)
    val background by animateColorAsState(
        // Прозрачная версия того же цвета: переход не «проваливается» через чёрный.
        if (current) colors.container else colors.container.copy(alpha = 0f),
        tween(200),
        label = "lineHighlight",
    )
    val textColor by animateColorAsState(
        if (current) colors.onContainer else MaterialTheme.colorScheme.onSurface,
        tween(200),
        label = "lineText",
    )
    val matchStyle = SpanStyle(
        background = MaterialTheme.colorScheme.inverseSurface,
        color = MaterialTheme.colorScheme.inverseOnSurface,
    )
    val text = remember(line.text, needle, matchStyle) { highlight(line.text, needle, matchStyle) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(StenografShapes.LineHighlight)
            .background(background)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpeakerDot(line.speakerId, size = 6)
            Spacer(Modifier.width(6.dp))
            Text(
                speakerLabel,
                style = StenografType.timecode.copy(fontWeight = StenografType.label.fontWeight),
                color = if (current) colors.onContainer else colors.dot,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                formatTimecode(line.startMs),
                style = StenografType.timecode,
                color = if (current) colors.onContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(text, style = StenografType.body, color = textColor)
    }
}

private fun highlight(text: String, needle: String?, style: SpanStyle): AnnotatedString {
    if (needle == null) return AnnotatedString(text)
    val haystack = normalizeForSearch(text)
    return buildAnnotatedString {
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) break
            append(text.substring(from, at))
            withStyle(style) { append(text.substring(at, at + needle.length)) }
            from = at + needle.length
        }
        append(text.substring(from))
    }
}

@Composable
private fun MessageState(title: String, body: String?, modifier: Modifier = Modifier) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, bottom = bottom),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = StenografType.emphasisTitle, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(body, style = StenografType.body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
