package org.cfm.catscriptor.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.cfm.catscriptor.domain.app.AppSettings
import org.cfm.catscriptor.domain.app.CorrectionLevel
import org.cfm.catscriptor.domain.app.ExportFormat
import org.cfm.catscriptor.domain.app.SettingsRepository

/** Настройки сохраняются до публикации нового состояния. */
internal class PersistentSettingsRepository(context: Context) : SettingsRepository {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(AppSettings(
        language = preferences.getString("language", "ru") ?: "ru",
        speakerCount = preferences.getInt("speakerCount", 0).takeIf { it > 0 },
        defaultExportFormat = runCatching {
            ExportFormat.valueOf(preferences.getString("exportFormat", "JSON") ?: "JSON")
        }.getOrDefault(ExportFormat.JSON),
        liveTextEnabled = preferences.getBoolean("liveTextEnabled", true),
        correctionLevel = readCorrectionLevel(),
    ))
    override val settings = state.asStateFlow()

    /** Старый булев переключатель: включено означало 1.5B, то есть «Умная». */
    private fun readCorrectionLevel(): CorrectionLevel {
        preferences.getString("correctionLevel", null)?.let { saved ->
            runCatching { CorrectionLevel.valueOf(saved) }.getOrNull()?.let { return it }
        }
        return if (preferences.getBoolean("textCorrectionEnabled", true)) CorrectionLevel.SMART else CorrectionLevel.OFF
    }

    @Synchronized
    override fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(state.value)
        check(preferences.edit()
            .putString("language", next.language)
            .putInt("speakerCount", next.speakerCount ?: 0)
            .putString("exportFormat", next.defaultExportFormat.name)
            .putBoolean("liveTextEnabled", next.liveTextEnabled)
            .putString("correctionLevel", next.correctionLevel.name)
            .commit()) { "Не удалось сохранить настройки" }
        state.value = next
    }
}
