package org.cfm.catscriptor.ui.home

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.cfm.catscriptor.R
import org.cfm.catscriptor.ui.components.formatTimecode
import org.cfm.catscriptor.ui.theme.StenografShapes
import org.cfm.catscriptor.ui.theme.StenografType
import org.cfm.catscriptor.viewmodel.home.LevelMeter
import org.cfm.catscriptor.viewmodel.home.LevelReading
import org.cfm.catscriptor.viewmodel.home.LevelStatus
import org.cfm.catscriptor.viewmodel.home.RecorderSignal

/** Запись без Live-текста: крупный таймер, индикатор уровня и подсказка. */
@Composable
internal fun RecordingPanel(
    paused: Boolean,
    signal: () -> RecorderSignal?,
    contentPadding: PaddingValues,
) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(
        contentPadding = contentPadding,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        item(key = "status") {
            Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                if (paused) {
                    Box(Modifier.size(8.dp).border(1.5.dp, colors.error, CircleShape))
                } else {
                    PulsingDot()
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (paused) R.string.home_recording_on_pause else R.string.home_recording_now),
                    style = StenografType.meta,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        item(key = "timer") { LargeTimer(signal = signal, paused = paused) }
        item(key = "meter") { LevelMeterBlock(signal = signal, paused = paused) }
        item(key = "hint") {
            Surface(
                shape = StenografShapes.Card,
                color = colors.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.home_no_live_hint) + " · " +
                            stringResource(R.string.home_recording_foreground_hint),
                        style = StenografType.meta,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Крупный таймер. Объявления TalkBack делает таймер в «пилюле», здесь только описание. */
@Composable
private fun LargeTimer(signal: () -> RecorderSignal?, paused: Boolean) {
    val seconds by remember { derivedStateOf { (signal()?.elapsedMs ?: 0L) / 1000 } }
    val description = recordingAnnouncement(seconds * 1000, paused)
    Text(
        formatTimecode(seconds * 1000),
        style = StenografType.largeTimer,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .padding(top = 4.dp)
            .clearAndSetSemantics { contentDescription = description },
    )
}

/**
 * 16 сегментов 12 × 8 dp. Опрос уровня 10 раз в секунду; сглаживание и выбор подписи
 * делает [LevelMeter], здесь только отрисовка.
 */
@Composable
private fun LevelMeterBlock(signal: () -> RecorderSignal?, paused: Boolean) {
    val colors = MaterialTheme.colorScheme
    val meter = remember { LevelMeter() }
    var reading by remember { mutableStateOf(LevelReading(0, LevelStatus.NORMAL)) }
    LaunchedEffect(paused) {
        // Время паузы не должно засчитываться как тишина или тихая речь.
        meter.reset()
        if (paused) {
            reading = LevelReading(0, LevelStatus.NORMAL)
        } else {
            while (true) {
                val current = signal()
                if (current != null) {
                    reading = meter.update(
                        levelDbfs = current.levelDbfs,
                        peakDbfs = current.peakDbfs,
                        speechDetected = current.speechDetected,
                        nowMs = SystemClock.elapsedRealtime(),
                    )
                }
                delay(LevelMeter.UPDATE_PERIOD_MS)
            }
        }
    }
    val litColor = if (reading.status.isWarning) colors.error else colors.primary
    val label = stringResource(
        when (reading.status) {
            LevelStatus.NORMAL -> R.string.home_level_normal
            LevelStatus.TOO_QUIET -> R.string.home_level_too_quiet
            LevelStatus.SILENCE -> R.string.home_level_silence
            LevelStatus.CLIPPING -> R.string.home_level_clipping
        },
    )
    Column(Modifier.padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.clearAndSetSemantics { },
        ) {
            repeat(meter.segments) { index ->
                Box(
                    Modifier
                        .size(width = 12.dp, height = 8.dp)
                        .background(
                            if (index < reading.litSegments) litColor else colors.surfaceContainerHighest,
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
        if (!paused) {
            // Подпись меняется редко, поэтому её можно объявлять сразу: это предупреждение пользователю.
            Text(
                label,
                style = StenografType.timecode,
                color = colors.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
