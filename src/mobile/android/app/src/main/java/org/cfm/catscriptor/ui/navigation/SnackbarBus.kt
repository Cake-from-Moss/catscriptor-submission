package org.cfm.catscriptor.ui.navigation

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Общий Snackbar над плавающей навигацией. Новое сообщение заменяет текущее,
 * иначе Snackbar встают в очередь и таймаут «Удалено · Отменить» теряет смысл.
 */
class AppSnackbar(val hostState: SnackbarHostState, private val scope: CoroutineScope) {
    fun show(
        message: String,
        actionLabel: String? = null,
        durationMs: SnackbarDuration = SnackbarDuration.Short,
        onAction: () -> Unit = {},
        onTimeout: () -> Unit = {},
        timeoutMs: Long? = null,
    ) {
        hostState.currentSnackbarData?.dismiss()
        scope.launch {
            // SnackbarDuration знает только 4 и 10 с; точный таймаут (например, 5 с у «Отменить»)
            // делаем через Indefinite и отмену показа.
            val result = if (timeoutMs == null) {
                hostState.showSnackbar(message, actionLabel, duration = durationMs)
            } else {
                withTimeoutOrNull(timeoutMs) {
                    hostState.showSnackbar(message, actionLabel, duration = SnackbarDuration.Indefinite)
                } ?: SnackbarResult.Dismissed
            }
            when (result) {
                SnackbarResult.ActionPerformed -> onAction()
                SnackbarResult.Dismissed -> onTimeout()
            }
        }
    }
}

val LocalAppSnackbar = compositionLocalOf<AppSnackbar> { error("AppSnackbar не предоставлен") }
