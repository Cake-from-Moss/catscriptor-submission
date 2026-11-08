package org.cfm.catscriptor.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Формы дизайна. Всё нажимаемое — пилюля или круг; эти радиусы только для контейнеров. */
object StenografShapes {
    val Card = RoundedCornerShape(28.dp)
    val TopSheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val LineHighlight = RoundedCornerShape(24.dp)
    val JsonPreview = RoundedCornerShape(20.dp)
    val Pill = RoundedCornerShape(50)
    val RecordPill = RoundedCornerShape(44.dp)
}

/** Отступы и размеры плавающей навигации. */
object StenografDimens {
    val ScreenPadding = 16.dp
    val GroupGap = 24.dp
    val InnerGap = 8.dp
    val BottomBarHeight = 72.dp
    val BottomBarMargin = 16.dp
    val MinTouch = 48.dp
}
