package org.cfm.catscriptor.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext

private val LocalIsDarkTheme = compositionLocalOf { false }

@Composable
fun StenografTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> StenografDarkColors
        else -> StenografLightColors
    }
    CompositionLocalProvider(
        LocalIsDarkTheme provides darkTheme,
        LocalReduceMotion provides rememberReduceMotion(),
    ) {
        MaterialTheme(colorScheme = colors, typography = StenografTypography, content = content)
    }
}

/** Цвета спикера с учётом текущей темы. */
@Composable
@ReadOnlyComposable
fun speakerColors(speakerId: String): SpeakerColors =
    speakerColorsFor(speakerId, LocalIsDarkTheme.current)
