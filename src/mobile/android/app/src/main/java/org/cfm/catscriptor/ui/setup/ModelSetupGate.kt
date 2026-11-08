package org.cfm.catscriptor.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import org.cfm.catscriptor.R
import org.cfm.catscriptor.viewmodel.setup.ModelSetupState
import org.cfm.catscriptor.viewmodel.setup.ModelSetupViewModel

/**
 * Пропускает [content] только когда модели на месте. В сборке `full` это всегда так; в `lite` при первом
 * запуске показывает экран скачивания моделей.
 */
@Composable
fun ModelSetupGate(content: @Composable () -> Unit) {
    val viewModel: ModelSetupViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val current = state) {
        ModelSetupState.Ready -> content()
        else -> ModelSetupScreen(current, onStart = viewModel::start, onCancel = viewModel::cancel)
    }
}

@Composable
private fun ModelSetupScreen(state: ModelSetupState, onStart: () -> Unit, onCancel: () -> Unit) {
    // Экран не должен гаснуть, пока идёт скачивание ~1,9 ГБ.
    val view = LocalView.current
    val downloading = state is ModelSetupState.Downloading
    DisposableEffect(downloading) {
        view.keepScreenOn = downloading
        onDispose { view.keepScreenOn = false }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.models_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(12.dp))
            when (state) {
                ModelSetupState.Checking, ModelSetupState.Ready -> CircularProgressIndicator()
                is ModelSetupState.NeedsDownload -> {
                    Body(stringResource(R.string.models_explanation, megabytes(state.totalBytes)))
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.models_download))
                    }
                }
                is ModelSetupState.Downloading -> {
                    val fraction = if (state.totalBytes > 0) state.doneBytes.toFloat() / state.totalBytes else 0f
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Body(
                        stringResource(
                            R.string.models_progress,
                            megabytes(state.doneBytes), megabytes(state.totalBytes), (fraction * 100).toInt(),
                        ),
                    )
                    if (state.fileName.isNotEmpty()) Body(state.fileName)
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.models_cancel))
                    }
                }
                is ModelSetupState.Failed -> {
                    Body(stringResource(R.string.models_failed, state.message), error = true)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.models_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun Body(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.0f", bytes / (1024.0 * 1024.0))
