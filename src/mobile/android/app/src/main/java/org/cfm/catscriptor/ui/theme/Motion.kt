package org.cfm.catscriptor.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Кривые и длительности M3. */
object StenografMotion {
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val TAB_FADE_THROUGH_MS = 300
    const val COLOR_CHANGE_MS = 600
    const val FADE_SHORT_MS = 150
    const val FADE_IN_MS = 200
}

/** true, если в системе отключены анимации: вращение и «дыхание» выключаются. */
val LocalReduceMotion = compositionLocalOf { false }

@Composable
internal fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
