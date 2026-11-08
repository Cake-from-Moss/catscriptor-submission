package org.cfm.catscriptor.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Запасная палитра дизайна: Android ниже 12 или выключенный динамический цвет. */
val StenografLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    background = Color(0xFFFEF7FF),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFEF7FF),
    surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainer = Color(0xFFF3EDF7),
    surfaceContainerHigh = Color(0xFFECE6F0),
    surfaceContainerHighest = Color(0xFFE6E0E9),
    onSurface = Color(0xFF1D1B20),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
    error = Color(0xFFB3261E),
)

val StenografDarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF141218),
    surfaceContainerLow = Color(0xFF1D1B20),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36343B),
    onSurface = Color(0xFFE6E0E9),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    error = Color(0xFFF2B8B5),
)

/** Цвета одного спикера: контейнер и текст на нём, точка для маркера и дорожки плеера. */
@Immutable
data class SpeakerColors(val container: Color, val onContainer: Color, val dot: Color)

private val LightSpeakers = listOf(
    SpeakerColors(Color(0xFFEADDFF), Color(0xFF21005D), Color(0xFF6750A4)),
    SpeakerColors(Color(0xFFFFD8E4), Color(0xFF31111D), Color(0xFFB0546F)),
    SpeakerColors(Color(0xFFC8E6FF), Color(0xFF001E30), Color(0xFF00658F)),
    // Для 4-го и 5-го спикера дизайн задаёт оранжевый и зелёный тональные контейнеры.
    SpeakerColors(Color(0xFFFFDBC8), Color(0xFF341100), Color(0xFF9A4A1C)),
    SpeakerColors(Color(0xFFC4EED0), Color(0xFF00210F), Color(0xFF2E6B47)),
)

private val DarkSpeakers = listOf(
    SpeakerColors(Color(0xFF4F378B), Color(0xFFEADDFF), Color(0xFFD0BCFF)),
    SpeakerColors(Color(0xFF633B48), Color(0xFFFFD8E4), Color(0xFFEFB8C8)),
    SpeakerColors(Color(0xFF004C6E), Color(0xFFC8E6FF), Color(0xFF8ECDFF)),
    SpeakerColors(Color(0xFF713611), Color(0xFFFFDBC8), Color(0xFFFFB68E)),
    SpeakerColors(Color(0xFF14512F), Color(0xFFC4EED0), Color(0xFFA9D2B5)),
)

/** Индекс считается от N в speaker_N; при большем числе спикеров цвета идут по кругу. */
fun speakerColorsFor(speakerId: String, dark: Boolean): SpeakerColors {
    val palette = if (dark) DarkSpeakers else LightSpeakers
    val number = speakerId.substringAfterLast('_').toIntOrNull() ?: 1
    return palette[(number - 1).mod(palette.size)]
}
