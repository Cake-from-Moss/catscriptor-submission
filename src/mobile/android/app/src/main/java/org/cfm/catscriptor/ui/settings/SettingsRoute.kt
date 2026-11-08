package org.cfm.catscriptor.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.cfm.catscriptor.app.AppGraph
import org.cfm.catscriptor.ui.navigation.AppNavigator
import org.cfm.catscriptor.viewmodel.settings.SettingsViewModel

@Composable
fun SettingsRoute(graph: AppGraph, navigator: AppNavigator) {
    val viewModel = viewModel(key = "settings") { SettingsViewModel(graph.settings, graph.processing) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = remember(viewModel, navigator) {
        SettingsActions(
            onBack = navigator.back,
            onLanguageChange = viewModel::onLanguageChange,
            onSpeakerCountChange = viewModel::onSpeakerCountChange,
            onExportFormatChange = viewModel::onExportFormatChange,
            onLiveTextChange = viewModel::onLiveTextChange,
            onCorrectionLevelChange = viewModel::onCorrectionLevelChange,
        )
    }
    SettingsScreen(state = state, actions = actions)
}
