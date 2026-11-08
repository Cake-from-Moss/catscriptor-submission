package org.cfm.catscriptor.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import org.cfm.catscriptor.domain.pipeline.AudioInput

/**
 * Регистрируется в onCreate до STARTED с постоянным ключом. Отмена выбора не вызывает callback.
 * Разрешение SAF временное: вызывающий код должен сразу декодировать документ, пока действует grant.
 * Контроллер не сохраняет разрешение, не запускает корутины и не владеет декодированным PCM.
 */
class AudioImportController(
    activity: ComponentActivity,
    private val onResult: (Result<AudioInput>) -> Unit,
    key: String = "audio-import",
) {
    private val launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>

    init {
        checkMainThread()
        check(!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            "Контроллер импорта нужно создать до STARTED"
        }
        launcher = activity.activityResultRegistry.register(key, activity, LocalAudioDocument()) { uri ->
            if (uri != null) {
                onResult(runCatching {
                    require(uri.scheme == "content") { "Можно импортировать только локальный документ SAF" }
                    AudioInput(uri.toString())
                })
            }
        }
    }

    fun launch() {
        checkMainThread()
        try {
            launcher.launch(arrayOf("audio/*", "application/ogg"))
        } catch (error: Exception) {
            onResult(Result.failure(error))
        }
    }
}

internal class LocalAudioDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

private fun checkMainThread() {
    check(Looper.myLooper() == Looper.getMainLooper()) { "Импорт запускается из главного потока" }
}
