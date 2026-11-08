package org.cfm.catscriptor.export

import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.cfm.catscriptor.domain.pipeline.Transcript
import org.cfm.catscriptor.domain.pipeline.TranscriptFormat

/**
 * Создать один раз в onCreate до STARTED, с одинаковым ключом после пересоздания Activity.
 * Состояние и запись переживают смену конфигурации, но не смерть процесса.
 * Не сохраняет URI-разрешение: запись выполняется сразу после выбора документа.
 */
class TranscriptExportController(
    activity: ComponentActivity,
    writer: ContentResolverTranscriptWriter,
    private val onResult: (Result<Uri>) -> Unit,
    key: String = "transcript-export",
) {
    private val state: ExportState
    private val jsonLauncher: androidx.activity.result.ActivityResultLauncher<String>
    private val textLauncher: androidx.activity.result.ActivityResultLauncher<String>

    init {
        checkMainThread()
        check(!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            "Контроллер экспорта нужно создать до STARTED"
        }
        state = ViewModelProvider(activity)[key, ExportState::class.java]
        state.listener = onResult
        jsonLauncher = activity.activityResultRegistry.register(
            "$key-json", activity, ActivityResultContracts.CreateDocument("application/json"),
        ) { uri -> state.selected(uri, writer) }
        textLauncher = activity.activityResultRegistry.register(
            "$key-text", activity, ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri -> state.selected(uri, writer) }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                state.deliver()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                if (state.listener === onResult) state.listener = null
            }
        })
    }

    /** false означает, что выбор документа или запись уже выполняются. Отмена выбора без события. */
    fun export(transcript: Transcript, format: TranscriptFormat, suggestedName: String): Boolean {
        checkMainThread()
        if (state.pending != null) return false
        state.pending = ExportRequest(transcript, format)
        try {
            when (format) {
                TranscriptFormat.JSON -> jsonLauncher.launch(suggestedName)
                TranscriptFormat.TEXT -> textLauncher.launch(suggestedName)
            }
        } catch (error: Exception) {
            state.pending = null
            if (error is CancellationException) throw error
            state.complete(Result.failure(error))
        }
        return true
    }
}

internal data class ExportRequest(val transcript: Transcript, val format: TranscriptFormat)

/** Хранит только один запрос; Activity и её ContentResolver в состоянии не сохраняются. */
class ExportState : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal var pending: ExportRequest? = null
    internal var listener: ((Result<Uri>) -> Unit)? = null
    private var writing = false
    private var result: Result<Uri>? = null

    internal fun selected(uri: Uri?, writer: ContentResolverTranscriptWriter) {
        val request = pending ?: return
        if (writing) return
        if (uri == null) {
            pending = null
            return
        }
        writing = true
        scope.launch {
            val outcome = try {
                writer.write(uri, request.transcript, request.format)
                Result.success(uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            } finally {
                pending = null
                writing = false
            }
            complete(outcome)
        }
    }

    internal fun complete(outcome: Result<Uri>) {
        result = outcome
        deliver()
    }

    internal fun deliver() {
        val callback = listener ?: return
        val outcome = result ?: return
        result = null
        callback(outcome)
    }

    override fun onCleared() {
        listener = null
        scope.cancel()
    }
}

private fun checkMainThread() {
    check(Looper.myLooper() == Looper.getMainLooper()) { "Экспорт запускается из главного потока" }
}
