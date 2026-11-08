package org.cfm.catscriptor.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.cfm.catscriptor.R
import org.cfm.catscriptor.domain.app.ProcessingJob
import org.cfm.catscriptor.domain.app.ProcessingStage
import org.cfm.catscriptor.domain.app.isOptional
import org.cfm.catscriptor.domain.app.RecordingSource
import org.cfm.catscriptor.domain.app.StageProgress
import org.cfm.catscriptor.domain.app.StageStatus
import org.cfm.catscriptor.ui.components.AppIcons
import org.cfm.catscriptor.ui.theme.LocalReduceMotion
import org.cfm.catscriptor.ui.theme.StenografShapes
import org.cfm.catscriptor.ui.theme.StenografType

/** После «Стоп» или импорта: карточка этапов. */
@Composable
internal fun ProcessingDetail(
    job: ProcessingJob,
    contentPadding: PaddingValues,
    onCancel: () -> Unit,
    onCollapse: () -> Unit,
    onRetry: () -> Unit,
    onSkipStage: (ProcessingStage) -> Unit,
    onOpenResult: () -> Unit,
) {
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
        item(key = "card") {
            ProcessingCard(
                job = job,
                onCancel = onCancel,
                onCollapse = onCollapse,
                onRetry = onRetry,
                onSkipStage = onSkipStage,
                onOpenResult = onOpenResult,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProcessingCard(
    job: ProcessingJob,
    onCancel: () -> Unit,
    onCollapse: () -> Unit,
    onRetry: () -> Unit,
    onSkipStage: (ProcessingStage) -> Unit,
    onOpenResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val progress by animateFloatAsState(
        job.overallFraction.coerceIn(0f, 1f),
        tween(PROGRESS_ANIMATION_MS),
        label = "detailProgress",
    )
    val failed = job.stages.firstOrNull { it.status is StageStatus.Failed }
    val source = stringResource(
        if (job.source == RecordingSource.FILE) R.string.home_source_meta_file else R.string.home_source_meta_microphone,
    )
    val meta = joinMeta(listOf(formatClock(job.durationMs), source, job.fileExtension?.let { ".${it.removePrefix(".")}" }))

    Surface(shape = StenografShapes.Card, color = colors.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(job.title, style = StenografType.emphasisTitle, color = colors.onSurface)
                    Spacer(Modifier.height(2.dp))
                    Text(meta, style = StenografType.timecode, color = colors.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.home_percent, percentOf(job.overallFraction)),
                    style = StenografType.processingPercent,
                    color = colors.primary,
                )
            }
            Spacer(Modifier.height(12.dp))
            ProgressLine(progress = { progress })
            Spacer(Modifier.height(8.dp))
            job.stages.forEach { stage -> StageRow(stage = stage, durationMs = job.durationMs) }
            Spacer(Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (failed != null) {
                    // Пропустить можно только необязательный этап (LLM-коррекцию), остальные нужны результату.
                    if (failed.stage.isOptional) {
                        TextButton(onClick = { onSkipStage(failed.stage) }) {
                            Text(stringResource(R.string.home_skip_stage), style = StenografType.label)
                        }
                    }
                    FilledTonalButton(onClick = onRetry) {
                        Text(stringResource(R.string.home_retry), style = StenografType.label)
                    }
                }
                if (job.stages.any { it.stage == ProcessingStage.TRANSCRIBE && it.status is StageStatus.Done }) {
                    FilledTonalButton(onClick = onOpenResult) {
                        Text(stringResource(R.string.home_open_asr_draft), style = StenografType.label)
                    }
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.home_cancel), style = StenografType.label)
                }
                FilledTonalButton(onClick = onCollapse) {
                    Text(stringResource(R.string.home_collapse), style = StenografType.label)
                }
            }
        }
    }
}

@Composable
private fun StageRow(stage: StageProgress, durationMs: Long) {
    val colors = MaterialTheme.colorScheme
    val status = stage.status
    val title = stringResource(stage.stage.titleRes())
    val subtitle = when (status) {
        is StageStatus.Running -> status.processedMs
            ?.let { stringResource(R.string.home_processed_of, formatClock(it), formatClock(durationMs)) }
            ?: stringResource(stage.stage.hintRes())
        is StageStatus.Failed -> status.message ?: stringResource(R.string.home_stage_failed)
        StageStatus.Skipped -> stringResource(R.string.home_stage_skipped)
        else -> stringResource(stage.stage.hintRes())
    }
    val time = when (status) {
        is StageStatus.Done -> formatClock(status.elapsedMs)
        is StageStatus.Running -> formatClock(status.elapsedMs)
        else -> null
    }
    val stateText = stringResource(
        when (status) {
            is StageStatus.Done -> R.string.home_stage_state_done
            is StageStatus.Running -> R.string.home_stage_state_running
            is StageStatus.Failed -> R.string.home_stage_state_failed
            StageStatus.Skipped -> R.string.home_stage_state_skipped
            StageStatus.Pending -> R.string.home_stage_state_pending
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) { stateDescription = stateText },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { StageIndicator(status) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = if (status is StageStatus.Running) {
                    StenografType.body.copy(fontWeight = FontWeight.Medium)
                } else {
                    StenografType.body
                },
                color = colors.onSurface,
            )
            Text(
                subtitle,
                style = StenografType.timecode,
                color = if (status is StageStatus.Failed) colors.error else colors.onSurfaceVariant,
            )
        }
        if (time != null) {
            Spacer(Modifier.width(12.dp))
            Text(time, style = StenografType.timecode, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun StageIndicator(status: StageStatus) {
    val colors = MaterialTheme.colorScheme
    when (status) {
        is StageStatus.Done -> DoneMark()
        is StageStatus.Running -> StageSpinner()
        is StageStatus.Failed -> Icon(
            AppIcons.Warning,
            contentDescription = null,
            tint = colors.error,
            modifier = Modifier.size(20.dp),
        )
        StageStatus.Skipped -> Box(
            Modifier.size(20.dp).border(1.5.dp, colors.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Remove, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
        }
        StageStatus.Pending -> Box(Modifier.size(20.dp).border(1.5.dp, colors.outlineVariant, CircleShape))
    }
}

/** Галочка в круге появляется с лёгким отскоком: scale 0.6 → 1 за 200 мс. */
@Composable
private fun DoneMark() {
    val reduceMotion = LocalReduceMotion.current
    val scale = remember { Animatable(if (reduceMotion) 1f else 0.6f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, tween(200, easing = OvershootEasing)) }
    Box(
        Modifier
            .size(20.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(AppIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
    }
}

/**
 * Кольцо 20 dp толщиной 3, один оборот в секунду и без процента. Свой индикатор вместо
 * CircularProgressIndicator: у стандартного другой период и меняющаяся длина дуги.
 */
@Composable
private fun StageSpinner() {
    val reduceMotion = LocalReduceMotion.current
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(reduceMotion) {
        if (!reduceMotion) {
            while (true) {
                rotation.snapTo(0f)
                rotation.animateTo(360f, tween(1_000, easing = LinearEasing))
            }
        }
    }
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(20.dp)) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2
        rotate(rotation.value) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** Перелёт как у пружины из макета: cubic-bezier(.34, 1.56, .64, 1). */
private val OvershootEasing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
