package org.cfm.catscriptor.ui.home

import android.view.HapticFeedbackConstants
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.cfm.catscriptor.ui.components.AppIcons
import org.cfm.catscriptor.ui.theme.LocalReduceMotion
import org.cfm.catscriptor.ui.theme.StenografMotion

/**
 * «Печенька» с [lobes] волнами, которая при [progress] = 1 становится кругом.
 * Радиус в долях стороны: r(θ) = (0.45 − 0.03·p) + 0.045·(1 − p)·cos(lobes·θ),
 * то есть в конце морфинга круг диаметром 0.84 стороны (≈ 64 dp у кнопки 76 dp).
 */
class CookieShape(private val progress: Float, private val lobes: Int = 9) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val side = minOf(size.width, size.height)
        val cx = size.width / 2
        val cy = size.height / 2
        val base = (0.45f - 0.03f * progress) * side
        val amplitude = 0.045f * (1f - progress) * side
        val path = Path()
        for (i in 0..SEGMENTS) {
            val t = i.toFloat() / SEGMENTS * 2f * PI.toFloat()
            val r = base + amplitude * cos(lobes * t)
            val x = cx + r * cos(t)
            val y = cy + r * sin(t)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }

    private companion object {
        // 120 точек достаточно, чтобы при 76 dp волны выглядели гладкими.
        const val SEGMENTS = 120
    }
}

/**
 * Кнопка записи 76 dp: в ожидании вращающаяся «печенька» primary с микрофоном,
 * во время записи «дышащий» круг onPrimary со «стопом». Вращается только слой формы,
 * иконка лежит отдельным слоем и всегда стоит ровно.
 *
 * [onClick] = null, когда нажатие обрабатывает родитель (в ожидании кликабельна вся «пилюля»).
 */
@Composable
fun RecordButton(
    recording: Boolean,
    contentDescription: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val colors = MaterialTheme.colorScheme

    val morph by animateFloatAsState(
        targetValue = if (recording) 1f else 0f,
        // Пружина с перелётом: круг на мгновение «проскакивает» и возвращается.
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow),
        label = "recordMorph",
    )
    val fill by animateColorAsState(
        targetValue = if (recording) colors.onPrimary else colors.primary,
        animationSpec = tween(StenografMotion.COLOR_CHANGE_MS, easing = StenografMotion.Emphasized),
        label = "recordFill",
    )

    val rotation = remember { Animatable(0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            rotation.snapTo(0f)
        } else {
            while (true) {
                rotation.snapTo(0f)
                rotation.animateTo(360f, tween(ROTATION_PERIOD_MS, easing = LinearEasing))
            }
        }
    }

    val breath = remember { Animatable(1f) }
    LaunchedEffect(recording, reduceMotion) {
        if (recording && !reduceMotion) {
            while (true) {
                breath.animateTo(BREATH_SCALE, tween(BREATH_HALF_PERIOD_MS, easing = FastOutSlowInEasing))
                breath.animateTo(1f, tween(BREATH_HALF_PERIOD_MS, easing = FastOutSlowInEasing))
            }
        } else {
            breath.animateTo(1f, tween(StenografMotion.FADE_SHORT_MS))
        }
    }

    // Отклик на старт и стоп, но не на первое появление экрана. CONFIRM есть с API 30 = minSdk.
    val view = LocalView.current
    val lastRecording = remember { booleanArrayOf(recording) }
    LaunchedEffect(recording) {
        if (lastRecording[0] != recording) {
            lastRecording[0] = recording
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
    }

    Box(
        modifier = modifier
            .size(BUTTON_SIZE)
            .graphicsLayer {
                scaleX = breath.value
                scaleY = breath.value
            },
        contentAlignment = Alignment.Center,
    ) {
        val shapeLayer = Modifier
            .matchParentSize()
            .graphicsLayer {
                rotationZ = rotation.value
                shape = CookieShape(morph)
                clip = true
            }
            .drawBehind { drawRect(fill) }
        Box(
            if (onClick != null) {
                shapeLayer
                    .clickable(role = Role.Button, onClick = onClick)
                    .semantics { contentDescription?.let { this.contentDescription = it } }
            } else {
                shapeLayer
            },
        )
        Crossfade(
            targetState = recording,
            animationSpec = tween(StenografMotion.FADE_SHORT_MS),
            label = "recordIcon",
        ) { isRecording ->
            if (isRecording) {
                androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(19.dp)
                            .background(colors.primary, RoundedCornerShape(6.dp)),
                    )
                    androidx.compose.material3.Text(
                        "Стоп",
                        color = colors.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                }
            } else {
                Icon(
                    AppIcons.Mic,
                    contentDescription = null,
                    tint = colors.onPrimary,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}

private val BUTTON_SIZE = 76.dp
private const val ROTATION_PERIOD_MS = 12_000
private const val BREATH_SCALE = 1.06f
private const val BREATH_HALF_PERIOD_MS = 600
