package org.cfm.catscriptor.app

import org.cfm.catscriptor.domain.app.ExportGateway
import org.cfm.catscriptor.domain.app.PlaybackController
import org.cfm.catscriptor.domain.app.ProcessingController
import org.cfm.catscriptor.domain.app.RecorderController
import org.cfm.catscriptor.domain.app.RecordingRepository
import org.cfm.catscriptor.domain.app.SettingsRepository

/**
 * Единственная точка, где UI получает реализации. Чтобы привязать логику, достаточно заменить
 * реализацию здесь: экраны и ViewModel знают только интерфейсы. См. docs/ui-integration.md.
 */
interface AppGraph {
    val recordings: RecordingRepository
    val processing: ProcessingController
    val recorder: RecorderController
    val playback: PlaybackController
    val export: ExportGateway
    val settings: SettingsRepository
}
