package org.cfm.catscriptor.viewmodel.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.cfm.catscriptor.domain.app.AppSettings
import org.cfm.catscriptor.domain.app.CorrectionLevel
import org.cfm.catscriptor.domain.app.ExportFormat
import org.cfm.catscriptor.domain.app.ModelsStatus
import org.cfm.catscriptor.domain.app.ProcessingController
import org.cfm.catscriptor.domain.app.SettingsRepository

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val modelsStatus: ModelsStatus = ModelsStatus.Ready,
)

/** Языки, которые поддерживает модель распознавания; GigaAM обучена только на русском. */
val SUPPORTED_LANGUAGES = listOf("ru")

/** Варианты числа спикеров; null — «авто», диаризация сама оценивает число голосов. */
val SPEAKER_COUNT_OPTIONS: List<Int?> = listOf(null, 1, 2, 3, 4, 5)

class SettingsViewModel(
    private val repository: SettingsRepository,
    processing: ProcessingController,
) : ViewModel() {
    val state: StateFlow<SettingsUiState> =
        combine(repository.settings, processing.modelsStatus) { settings, models ->
            SettingsUiState(settings, models)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsUiState(repository.settings.value, processing.modelsStatus.value),
        )

    fun onLanguageChange(language: String) {
        if (language in SUPPORTED_LANGUAGES) repository.update { it.copy(language = language) }
    }

    fun onSpeakerCountChange(count: Int?) = repository.update { it.copy(speakerCount = count) }

    fun onExportFormatChange(format: ExportFormat) = repository.update { it.copy(defaultExportFormat = format) }

    fun onLiveTextChange(enabled: Boolean) = repository.update { it.copy(liveTextEnabled = enabled) }

    fun onCorrectionLevelChange(level: CorrectionLevel) = repository.update { it.copy(correctionLevel = level) }
}
