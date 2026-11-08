package org.cfm.catscriptor.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.cfm.catscriptor.R
import org.cfm.catscriptor.ui.components.formatTimecode
import org.cfm.catscriptor.ui.theme.StenografMotion
import org.cfm.catscriptor.ui.theme.StenografShapes
import org.cfm.catscriptor.ui.theme.StenografType
import org.cfm.catscriptor.viewmodel.home.LEVEL_FLOOR_DBFS
import org.cfm.catscriptor.viewmodel.home.RecorderSignal
import org.cfm.catscriptor.viewmodel.home.WaveformHistory

/** Что написано в «пилюле» в ожидании. */
enum class MicAccess {
    GRANTED,

    /** Разрешения нет, но его ещё можно запросить системным диалогом. */
    REQUEST,

    /** Пользователь запретил «больше не спрашивать»: остаются только настройки системы. */
    OPEN_SETTINGS,
}

/**
 * «Пилюля» записи 88 dp. В ожидании нажимается целиком; во время записи нажимается только
 * круглая кнопка «стоп», чтобы случайное касание волны не остановило запись.
 */
@Composable
fun RecordPill(
    recording: Boolean,
    paused: Boolean,
    queueBusy: Boolean,
    micAccess: MicAccess,
    signal: () -> RecorderSignal?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(
        targetValue = if (recording) colors.primary else colors.primaryContainer,
        animationSpec = tween(StenografMotion.COLOR_CHANGE_MS, easing = StenografMotion.Emphasized),
        label = "pillBackground",
    )
    val startLabel = stringResource(R.string.home_start_recording)
    val stopLabel = stringResource(R.string.home_stop_recording)
    val title = if (micAccess == MicAccess.GRANTED) startLabel else stringResource(R.string.home_mic_permission_title)
    val subtitle = when (micAccess) {
        MicAccess.GRANTED -> stringResource(if (queueBusy) R.string.home_will_be_queued else R.string.home_on_device_only)
        MicAccess.REQUEST -> stringResource(R.string.home_mic_permission_request)
        MicAccess.OPEN_SETTINGS -> stringResource(R.string.home_open_settings)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(StenografShapes.RecordPill)
            .background(background)
            .then(
                if (recording) {
                    Modifier
                } else {
                    Modifier.clickable(onClickLabel = title, role = Role.Button, onClick = onStart)
                },
            )
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecordButton(
            recording = recording,
            contentDescription = if (recording) stopLabel else null,
            onClick = if (recording) onStop else null,
        )
        AnimatedContent(
            targetState = recording,
            transitionSpec = {
                fadeIn(tween(StenografMotion.FADE_IN_MS, delayMillis = 100)) togetherWith
                    fadeOut(tween(StenografMotion.FADE_SHORT_MS))
            },
            modifier = Modifier.weight(1f).padding(start = 12.dp, end = 18.dp),
            contentAlignment = Alignment.CenterStart,
            label = "pillContent",
        ) { isRecording ->
            if (isRecording) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Waveform(
                        paused = paused,
                        signal = signal,
                        modifier = Modifier.weight(1f).height(46.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    PillTimer(signal = signal, paused = paused)
                }
            } else {
                Column(verticalArrangement = Arrangement.Center) {
                    Text(
                        title,
                        style = StenografType.emphasisTitle,
                        color = colors.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        subtitle,
                        style = StenografType.meta,
                        color = if (micAccess == MicAccess.OPEN_SETTINGS) colors.primary else colors.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Волна из реального уровня сигнала: каждые 50 мс столбики сдвигаются влево, справа появляется
 * новый с высотой по текущему уровню. На паузе волна замирает.
 */
@Composable
private fun Waveform(paused: Boolean, signal: () -> RecorderSignal?, modifier: Modifier = Modifier) {
    val history = remember { WaveformHistory() }
    val bars = remember { mutableStateOf(history.snapshot()) }
    LaunchedEffect(paused) {
        while (!paused) {
            history.push(signal()?.levelDbfs ?: LEVEL_FLOOR_DBFS)
            bars.value = history.snapshot()
            delay(WaveformHistory.SHIFT_PERIOD_MS)
        }
    }
    val barColor = MaterialTheme.colorScheme.primaryContainer
    // Столбики только рисуются, поэтому состояние читается в фазе отрисовки, без рекомпозиции.
    Canvas(modifier.clearAndSetSemantics { }) {
        val values = bars.value
        val slot = size.width / values.size
        val barWidth = 3.dp.toPx()
        val minHeight = 12.dp.toPx()
        val maxHeight = minOf(46.dp.toPx(), size.height)
        val radius = CornerRadius(2.dp.toPx())
        values.forEachIndexed { index, fraction ->
            val barHeight = minHeight + (maxHeight - minHeight) * fraction
            drawRoundRect(
                color = barColor,
                topLeft = Offset(slot * index + (slot - barWidth) / 2, (size.height - barHeight) / 2),
                size = Size(barWidth, barHeight),
                cornerRadius = radius,
            )
        }
    }
}

/**
 * Таймер в «пилюле». Для TalkBack это live region, но его описание меняется раз в 10 с,
 * иначе запись объявлялась бы каждую секунду.
 */
@Composable
private fun PillTimer(signal: () -> RecorderSignal?, paused: Boolean) {
    val seconds by remember { derivedStateOf { (signal()?.elapsedMs ?: 0L) / 1000 } }
    val announcedTens by remember { derivedStateOf { seconds / ANNOUNCE_PERIOD_S * ANNOUNCE_PERIOD_S } }
    val announcement = recordingAnnouncement(announcedTens * 1000, paused)
    Text(
        formatTimecode(seconds * 1000),
        style = StenografType.pillTimer,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = announcement
            liveRegion = LiveRegionMode.Polite
        },
    )
}

private const val ANNOUNCE_PERIOD_S = 10L
