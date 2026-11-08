package org.cfm.catscriptor.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import org.cfm.catscriptor.ui.theme.StenografShapes
import kotlin.math.roundToInt

/** Жест только раскрывает действие: само удаление требует отдельного нажатия. */
@Composable
fun SwipeToRevealDelete(
    onDelete: () -> Unit,
    onRename: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val width = 112.dp
    val widthPx = with(LocalDensity.current) { width.toPx() }
    var target by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val animated by animateFloatAsState(target, tween(if (dragging) 0 else 180), label = "deleteReveal")
    BackHandler(target != 0f) { target = 0f }
    LaunchedEffect(onRename != null) {
        if (onRename == null && target > 0f) target = 0f
    }
    Box(modifier.fillMaxWidth().clip(StenografShapes.Card).semantics {
        customActions = listOf(
            CustomAccessibilityAction("Переименовать запись") { onRename?.invoke(); onRename != null },
            CustomAccessibilityAction("Показать кнопку удаления") { target = -widthPx; true },
        )
    }) {
        if (target < 0f || animated < 0f) {
            Box(Modifier.matchParentSize()) {
            TextButton(
                onClick = onDelete,
                enabled = target <= -widthPx && !dragging,
                modifier = Modifier.align(Alignment.CenterEnd).width(width).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.error),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(AppIcons.Delete, null, tint = MaterialTheme.colorScheme.onError)
                    Text("Удалить", color = MaterialTheme.colorScheme.onError)
                }
            }
            }
        }
        if (onRename != null && (target > 0f || animated > 0f)) {
            Box(Modifier.matchParentSize()) {
                TextButton(
                    onClick = { onRename() },
                    enabled = target >= widthPx && !dragging,
                    modifier = Modifier.align(Alignment.CenterStart).width(width).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(AppIcons.Edit, null, tint = MaterialTheme.colorScheme.onPrimary)
                        Text(
                            "Переименовать",
                            color = MaterialTheme.colorScheme.onPrimary,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
        Box(Modifier.offset { IntOffset(animated.roundToInt(), 0) }
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(widthPx, onRename != null) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragCancel = { dragging = false; target = 0f },
                    onDragEnd = {
                        dragging = false
                        target = when {
                            target < -widthPx / 2 -> -widthPx
                            onRename != null && target > widthPx / 2 -> widthPx
                            else -> 0f
                        }
                    },
                ) { change, delta ->
                    change.consume()
                    target = (target + delta).coerceIn(-widthPx, if (onRename == null) 0f else widthPx)
                }
            }) {
                content()
                if (target != 0f && !dragging) Box(Modifier.matchParentSize().clickable { target = 0f })
            }
    }
}
