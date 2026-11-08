package org.cfm.catscriptor.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cfm.catscriptor.R
import org.cfm.catscriptor.ui.components.AppIcons
import org.cfm.catscriptor.ui.theme.StenografDimens

/** Плавающая навигация: высота 72, радиус 36, индикатор 56 × 32, бейдж 8 dp. */
@Composable
fun FloatingBottomBar(
    selected: TopTab,
    showProcessingBadge: Boolean,
    onSelect: (TopTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().height(StenografDimens.BottomBarHeight),
        shape = RoundedCornerShape(36.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            BarItem(
                label = stringResource(R.string.nav_record),
                icon = AppIcons.Mic,
                selected = selected == TopTab.HOME,
                badge = false,
                onClick = { onSelect(TopTab.HOME) },
                modifier = Modifier.weight(1f),
            )
            BarItem(
                label = stringResource(R.string.nav_recordings),
                icon = AppIcons.List,
                selected = selected == TopTab.RECORDINGS,
                badge = showProcessingBadge,
                onClick = { onSelect(TopTab.RECORDINGS) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BarItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    badge: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val indicator by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "navIndicator",
    )
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(36.dp))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(width = 56.dp, height = 32.dp).clip(CircleShape).background(indicator),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            }
            if (badge) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-12).dp, y = 2.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            fontSize = 12.sp,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
