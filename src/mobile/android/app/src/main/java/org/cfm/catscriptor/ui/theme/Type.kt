package org.cfm.catscriptor.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Табличные цифры, чтобы таймер и проценты не «дрожали» при смене значений. */
private const val TABULAR = "tnum"

/** Стили из таблицы типографики, у которых нет прямого соответствия в типографике M3. */
@Immutable
object StenografType {
    val screenTitle = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Normal)
    val largeTimer = TextStyle(
        fontSize = 57.sp, lineHeight = 64.sp, fontWeight = FontWeight.Normal,
        fontFeatureSettings = TABULAR, letterSpacing = 0.em,
    )
    val sheetTitle = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal)
    val processingPercent = TextStyle(
        fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TABULAR,
    )
    val pillTimer = TextStyle(
        fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TABULAR,
    )
    val emphasisTitle = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)
    val label = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val meta = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
    val timecode = TextStyle(
        fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = TABULAR,
    )
    val mono = TextStyle(
        fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal, fontFamily = FontFamily.Monospace,
    )
}

val StenografTypography = Typography(
    titleLarge = StenografType.sheetTitle,
    bodyLarge = StenografType.body,
    labelLarge = StenografType.label,
    bodySmall = StenografType.meta,
)
