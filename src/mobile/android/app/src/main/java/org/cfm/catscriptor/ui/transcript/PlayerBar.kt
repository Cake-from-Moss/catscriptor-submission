package org.cfm.catscriptor.ui.transcript

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import org.cfm.catscriptor.R
import org.cfm.catscriptor.domain.app.PlaybackState
import org.cfm.catscriptor.ui.components.AppIcons
import org.cfm.catscriptor.ui.components.formatTimecode
import org.cfm.catscriptor.ui.theme.StenografShapes
import org.cfm.catscriptor.ui.theme.StenografType
import org.cfm.catscriptor.ui.theme.speakerColors
import org.cfm.catscriptor.viewmodel.transcript.TrackSegment

/** Действия плеера; при недоступном аудио кнопки выключены, а не скрыты. */
class PlayerActions(
    val onPlayPause: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onSpeedCycle: () -> Unit,
    val onPreviousLine: () -> Unit,
    val onNextLine: () -> Unit,
    val onToggleFollow: () -> Unit,
)

/**
 * Плеер внизу расшифровки. Фон рисуется без обрезки по форме: подпись времени при перетаскивании
 * бегунка выходит над плеером и должна оставаться видимой.
 */
@Composable
fun PlayerBar(
    playback: PlaybackState,
    segments: List<TrackSegment>,
    followPlayback: Boolean,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    val enabled = playback.isAvailable
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        modifier
            .fillMaxWidth()
            .zIndex(1f)
            .background(MaterialTheme.colorScheme.surfaceContainer, StenografShapes.TopSheet)
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = bottomInset + 12.dp),
    ) {
        SeekTrack(
            segments = segments,
            durationMs = playback.durationMs,
            positionMs = playback.positionMs,
            enabled = enabled,
            onSeek = actions.onSeek,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TimeText(if (enabled) playback.positionMs else 0L)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (!enabled) {
                    Text(
                        stringResource(R.string.player_unavailable),
                        style = StenografType.timecode,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TimeText(playback.durationMs)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val speedText = stringResource(R.string.player_speed_value, speedLabel(playback.speed))
            val speedDescription = stringResource(R.string.player_speed, speedText)
            TextButton(
                onClick = actions.onSpeedCycle,
                enabled = enabled,
                shape = CircleShape,
                modifier = Modifier.widthIn(min = 56.dp).semantics { contentDescription = speedDescription },
            ) {
                Text(speedText, style = StenografType.label.copy(fontFeatureSettings = "tnum"))
            }
            IconButton(onClick = actions.onPreviousLine, enabled = enabled) {
                Icon(AppIcons.SkipPrevious, contentDescription = stringResource(R.string.player_prev_line))
            }
            PlayPauseButton(isPlaying = playback.isPlaying, enabled = enabled, onClick = actions.onPlayPause)
            IconButton(onClick = actions.onNextLine, enabled = enabled) {
                Icon(AppIcons.SkipNext, contentDescription = stringResource(R.string.player_next_line))
            }
            FollowToggle(checked = followPlayback, onClick = actions.onToggleFollow)
        }
    }
}

@Composable
private fun TimeText(ms: Long) {
    Text(
        formatTimecode(ms),
        style = StenografType.timecode,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** «1.0×», «1.25×», «2×»: целая скорость 1 пишется с десятичной, как в макете. */
private fun speedLabel(speed: Float): String = when {
    speed == 1f -> "1.0"
    speed % 1f == 0f -> speed.toInt().toString()
    else -> speed.toString()
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(if (isPlaying) R.string.player_pause else R.string.player_play)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .size(72.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = isPlaying,
                transitionSpec = {
                    (fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.6f))
                        .togetherWith(fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.6f))
                },
                label = "playPause",
            ) { playing ->
                Icon(
                    if (playing) AppIcons.Pause else AppIcons.Play,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

@Composable
private fun FollowToggle(checked: Boolean, onClick: () -> Unit) {
    val description = stringResource(if (checked) R.string.player_follow_on else R.string.player_follow_off)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (checked) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .size(width = 56.dp, height = 48.dp)
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(AppIcons.List, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Дорожка 10 dp из отрезков спикеров с зазором 1 dp и бегунок 4 × 28. При перетаскивании бегунок
 * расширяется до 6 dp, над пальцем видно время, а перемотка происходит при отпускании:
 * так плеер не дёргается на каждый пиксель движения.
 */
@Composable
private fun SeekTrack(
    segments: List<TrackSegment>,
    durationMs: Long,
    positionMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
) {
    var widthPx by remember { mutableIntStateOf(0) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val currentOnSeek by rememberUpdatedState(onSeek)
    val safeDuration = durationMs.coerceAtLeast(1)
    val fraction = dragFraction ?: (positionMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    val thumbWidth by animateDpAsState(if (dragFraction != null) 6.dp else 4.dp, tween(150), label = "thumb")
    val segmentColors = segments.map { speakerColors(it.speakerId).dot }
    val baseColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val thumbColor = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current
    val seekDescription = stringResource(R.string.player_seek)
    val positionText = formatTimecode(positionMs)

    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .onSizeChanged { widthPx = it.width }
            .semantics {
                contentDescription = seekDescription
                stateDescription = positionText
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                if (enabled) {
                    setProgress { target ->
                        currentOnSeek((target.coerceIn(0f, 1f) * safeDuration).toLong())
                        true
                    }
                }
            }
            .pointerInput(enabled, safeDuration) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    currentOnSeek(((offset.x / size.width).coerceIn(0f, 1f) * safeDuration).toLong())
                }
            }
            .pointerInput(enabled, safeDuration) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset -> dragFraction = (offset.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        dragFraction?.let { currentOnSeek((it * safeDuration).toLong()) }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null },
                ) { change, _ ->
                    change.consume()
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val trackHeight = 10.dp.toPx()
            val top = (size.height - trackHeight) / 2
            val radius = CornerRadius(trackHeight / 2)
            val gap = 1.dp.toPx()
            val trackPath = Path().apply {
                addRoundRect(RoundRect(0f, top, size.width, top + trackHeight, radius))
            }
            clipPath(trackPath, ClipOp.Intersect) {
                drawRect(baseColor, Offset(0f, top), Size(size.width, trackHeight))
                segments.forEachIndexed { index, segment ->
                    val start = segment.startMs.toFloat() / safeDuration * size.width
                    val end = segment.endMs.toFloat() / safeDuration * size.width
                    val width = (end - start - if (index < segments.lastIndex) gap else 0f).coerceAtLeast(0f)
                    drawRect(segmentColors[index], Offset(start, top), Size(width, trackHeight))
                }
            }
            val thumbPx = thumbWidth.toPx()
            val x = (fraction * size.width).coerceIn(thumbPx / 2, size.width - thumbPx / 2)
            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(x - thumbPx / 2, 0f),
                size = Size(thumbPx, size.height),
                cornerRadius = CornerRadius(thumbPx / 2),
            )
        }

        val dragging = dragFraction
        if (dragging != null && widthPx > 0) {
            var labelWidth by remember { mutableIntStateOf(0) }
            val x = (dragging * widthPx - labelWidth / 2f).coerceIn(0f, (widthPx - labelWidth).toFloat().coerceAtLeast(0f))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(x.roundToInt(), with(density) { (-28).dp.roundToPx() }) }
                    .onSizeChanged { labelWidth = it.width },
            ) {
                Text(
                    formatTimecode((dragging * safeDuration).toLong()),
                    style = StenografType.label.copy(fontFeatureSettings = "tnum"),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).heightIn(min = 20.dp),
                )
            }
        }
    }
}
