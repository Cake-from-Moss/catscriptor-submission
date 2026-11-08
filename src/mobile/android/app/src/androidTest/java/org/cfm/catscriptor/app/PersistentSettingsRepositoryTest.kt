package org.cfm.catscriptor.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.cfm.catscriptor.domain.app.CorrectionLevel
import org.cfm.catscriptor.domain.app.ExportFormat
import org.junit.Assert.*
import org.junit.Test

class PersistentSettingsRepositoryTest {
    @Test fun correctionOptionSurvivesRepositoryRecreation() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val preferenceName = "settings-test-${java.util.UUID.randomUUID()}"
        val context = object : ContextWrapper(application) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                application.getSharedPreferences(preferenceName, mode)
        }
        val repository = PersistentSettingsRepository(context)
        val original = repository.settings.value
        try {
            val changed = original.copy(language = "en", speakerCount = 3,
                defaultExportFormat = ExportFormat.entries.first { it != original.defaultExportFormat },
                liveTextEnabled = !original.liveTextEnabled, correctionLevel = CorrectionLevel.FAST)
            repository.update { changed }
            assertEquals(changed, PersistentSettingsRepository(context).settings.value)
            assertEquals(CorrectionLevel.FAST, PersistentSettingsRepository(context).settings.value.correctionLevel)
            repository.update { it.copy(correctionLevel = CorrectionLevel.OFF) }
            assertEquals(CorrectionLevel.OFF, PersistentSettingsRepository(context).settings.value.correctionLevel)
            repository.update { it.copy(speakerCount = null) }
            assertNull(PersistentSettingsRepository(context).settings.value.speakerCount)
        } finally {
            repository.update { original }
            application.deleteSharedPreferences(preferenceName)
        }
    }
}
