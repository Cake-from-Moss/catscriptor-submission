package org.cfm.catscriptor.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.cfm.catscriptor.R
import org.cfm.catscriptor.domain.app.LiveLine
import org.cfm.catscriptor.ui.components.AppIcons
import org.cfm.catscriptor.ui.components.formatTimecode
import org.cfm.catscriptor.ui.theme.LocalReduceMotion
import org.cfm.catscriptor.ui.theme.StenografMotion
import org.cfm.catscriptor.ui.theme.StenografType
import org.cfm.catscriptor.ui.theme.speakerColors

/**
 * Лента Live-текста. Сама прокручивается к новым репликам; если пользователь листает вверх,
 * автопрокрутка выключается до возврата вниз или нажатия «↓ К новому».
 */
@Composable
internal fun LiveFeed(
    lines: List<LiveLine>,
    contentPadding: PaddingValues,
    jumpButtonBottomPadding: Dp,
    status: String = "",
    recognizing: Boolean = false,
) {
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // Только жест пользователя вверх отключает автопрокрутку; программная прокрутка сюда не попадает.
    val userScroll = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0 && listState.canScrollBackward) {
                    follow = false
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { canScroll -> if (!canScroll) follow = true }
    }

    // Реплики, которые уже были на экране при входе, появляются без анимации.
    val animatedIds = remember { lines.mapTo(HashSet()) { it.id } }
    LaunchedEffect(lines) { animatedIds.retainAll(lines.mapTo(HashSet()) { it.id }) }
    val lastText = lines.lastOrNull()?.text
    LaunchedEffect(lines.size, lastText, follow) {
        if (follow && lines.isNotEmpty()) scrollToEnd(listState, lines.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        LiveHeader(Modifier.padding(horizontal = 16.dp))
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (recognizing) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(status, style = StenografType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize().nestedScroll(userScroll),
            ) {
                if (lines.isEmpty()) {
                    item(key = "waiting") {
                        Text(
                            stringResource(R.string.home_live_waiting),
                            style = StenografType.body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                items(lines, key = { it.id }) { line ->
                    val animateEnter = remember(line.id) { animatedIds.add(line.id) }
                    LiveLineItem(line = line, animateEnter = animateEnter)
                }
            }
            JumpToLatestButton(
                visible = !follow,
                onClick = {
                    follow = true
                    if (lines.isNotEmpty()) scope.launch { scrollToEnd(listState, lines.lastIndex) }
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = jumpButtonBottomPadding),
            )
        }
    }
}

@Composable
private fun JumpToLatestButton(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(StenografMotion.FADE_IN_MS)),
        exit = fadeOut(tween(StenografMotion.FADE_SHORT_MS)),
        modifier = modifier,
    ) {
        FilledTonalButton(onClick = onClick) {
            Icon(AppIcons.ArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.home_jump_to_latest), style = StenografType.label)
        }
    }
}

/** К последней реплике; если она выше экрана, докручиваем до её конца. */
private suspend fun scrollToEnd(state: LazyListState, lastIndex: Int) {
    state.animateScrollToItem(lastIndex)
    if (state.canScrollForward) state.scrollBy(TAIL_SCROLL_PX)
}

@Composable
private fun LiveHeader(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulsingDot()
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.home_live_header),
            style = StenografType.label,
            color = colors.onSurface,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        // Спикеры в Live предварительные: после остановки диаризация пересчитывается по всему файлу.
        Text(stringResource(R.string.home_live_draft), style = StenografType.timecode, color = colors.onSurfaceVariant)
    }
}

/** Красная точка «идёт запись»: прозрачность 1 ↔ 0.35 за 1.4 с. */
@Composable
internal fun PulsingDot(modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val alpha = if (reduceMotion) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "liveDot")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "liveDotAlpha",
        ).value
    }
    Box(
        modifier
            .size(8.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(MaterialTheme.colorScheme.error, CircleShape),
    )
}

@Composable
private fun LiveLineItem(line: LiveLine, animateEnter: Boolean) {
    val colors = MaterialTheme.colorScheme
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val enter = remember { Animatable(if (animateEnter && !reduceMotion) 0f else 1f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(StenografMotion.FADE_IN_MS, easing = StenografMotion.Emphasized)) }

    val textColor by animateColorAsState(
        if (line.isFinal) colors.onSurface else colors.onSurfaceVariant,
        tween(StenografMotion.FADE_SHORT_MS),
        label = "liveLineColor",
    )
    val speaker = speakerColors(line.speakerId)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * with(density) { 8.dp.toPx() }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (line.speakerId.isBlank()) "Речь" else speakerLabel(line.speakerId),
                style = StenografType.timecode.copy(fontWeight = FontWeight.Medium),
                color = speaker.onContainer,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(speaker.container)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(formatTimecode(line.startMs), style = StenografType.timecode, color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        if (line.isFinal) {
            Text(line.text, style = StenografType.body, color = textColor)
        } else {
            HypothesisText(line.text, textColor)
        }
    }
}

/** Гипотеза модели: текст и мигающий курсор 2 × 18 dp сразу за последним словом. */
@Composable
private fun HypothesisText(text: String, color: Color) {
    val density = LocalDensity.current
    val inline = remember(density) {
        mapOf(
            CURSOR_ID to InlineTextContent(
                Placeholder(
                    width = with(density) { 6.dp.toSp() },
                    height = with(density) { 18.dp.toSp() },
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) { BlinkingCursor() }
            },
        )
    }
    Text(
        buildAnnotatedString {
            append(text)
            // Compose запрещает пустой alternateText; пробел TalkBack не зачитывает.
            appendInlineContent(CURSOR_ID, " ")
        },
        style = StenografType.body,
        color = color,
        inlineContent = inline,
    )
}

/** Курсор мигает раз в секунду: полсекунды виден, полсекунды скрыт. */
@Composable
private fun BlinkingCursor() {
    val reduceMotion = LocalReduceMotion.current
    val alpha = if (reduceMotion) {
        1f
    } else {
        rememberInfiniteTransition(label = "cursor").animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                keyframes {
                    durationMillis = 1_000
                    1f at 0
                    1f at 499
                    0f at 500
                    0f at 999
                },
            ),
            label = "cursorAlpha",
        ).value
    }
    Box(
        Modifier
            .size(width = 2.dp, height = 18.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(MaterialTheme.colorScheme.primary),
    )
}

private const val CURSOR_ID = "cursor"

/** С запасом больше любой реплики; лишнее список отбросит сам, упёршись в конец. */
private const val TAIL_SCROLL_PX = 100_000f
