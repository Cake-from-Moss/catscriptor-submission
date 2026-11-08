package org.cfm.catscriptor.domain.app

import kotlinx.coroutines.flow.StateFlow

/**
 * Уровень правки текста локальной LLM: [OFF] — без правки, [FAST] — маленькая модель (0.5B),
 * [SMART] — крупная (1.5B). Для пользователя это «Выкл / Быстрая / Умная».
 */
enum class CorrectionLevel { OFF, FAST, SMART }

/** [speakerCount] равен null в режиме «авто», иначе это ожидаемое число спикеров. */
data class AppSettings(
    val language: String = "ru",
    val speakerCount: Int? = null,
    val defaultExportFormat: ExportFormat = ExportFormat.JSON,
    val liveTextEnabled: Boolean = true,
    val correctionLevel: CorrectionLevel = CorrectionLevel.SMART,
)

interface SettingsRepository {
    val settings: StateFlow<AppSettings>

    fun update(transform: (AppSettings) -> AppSettings)
}
