package org.cfm.catscriptor.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.cfm.catscriptor.R
import org.cfm.catscriptor.ui.theme.StenografType

/** Плашка прогресса внизу экрана (правка текста или обработка записи); «Скрыть» убирает её. */
@Composable
fun ProgressBanner(
    label: String,
    title: String,
    fraction: Float,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val surfaceModifier = modifier.fillMaxWidth()
    Surface(
        modifier = if (onClick != null) surfaceModifier.clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick) else surfaceModifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 3.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = StenografType.body)
                Text(
                    title,
                    style = StenografType.meta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp).clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
            IconButton(onClick = onHide) {
                Icon(AppIcons.Close, contentDescription = stringResource(R.string.correction_progress_hide))
            }
        }
    }
}
