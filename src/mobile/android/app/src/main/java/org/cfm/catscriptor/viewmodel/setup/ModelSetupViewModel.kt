package org.cfm.catscriptor.viewmodel.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cfm.catscriptor.BuildConfig
import org.cfm.catscriptor.native.models.BundledModelSpec
import org.cfm.catscriptor.native.models.ModelCatalog
import org.cfm.catscriptor.native.models.ModelDownloader

/** Состояние первого запуска сборки `lite`: готовы ли модели и идёт ли их скачивание. */
sealed interface ModelSetupState {
    data object Checking : ModelSetupState
    data object Ready : ModelSetupState
    data class NeedsDownload(val totalBytes: Long) : ModelSetupState
    data class Downloading(val doneBytes: Long, val totalBytes: Long, val fileName: String) : ModelSetupState
    data class Failed(val message: String, val totalBytes: Long) : ModelSetupState
}

/**
 * Отвечает за наличие моделей. В сборке `full` модели лежат в APK, и состояние сразу [ModelSetupState.Ready].
 * В `lite` при отсутствии файлов предлагает скачать их ([ModelDownloader]); пока модели не скачаны,
 * остальной интерфейс не показывается. Загрузка живёт в `viewModelScope` и прекращается вместе с экраном.
 */
class ModelSetupViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow<ModelSetupState>(
        if (BuildConfig.MODELS_BUNDLED) ModelSetupState.Ready else ModelSetupState.Checking,
    )
    val state: StateFlow<ModelSetupState> = mutableState

    private val downloader = ModelDownloader(File(application.noBackupFilesDir, "models"))
    private var specs: List<BundledModelSpec> = emptyList()
    private var job: Job? = null

    init {
        if (!BuildConfig.MODELS_BUNDLED) viewModelScope.launch { check() }
    }

    private suspend fun check() {
        val (loaded, missing) = withContext(Dispatchers.IO) {
            val loaded = ModelCatalog.load(getApplication<Application>().assets)
            loaded to downloader.missingBytes(loaded)
        }
        specs = loaded
        mutableState.value = if (missing == 0L) ModelSetupState.Ready else ModelSetupState.NeedsDownload(missing)
    }

    fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            val total = withContext(Dispatchers.IO) { downloader.missingBytes(specs) }
            mutableState.value = ModelSetupState.Downloading(0, total, "")
            try {
                downloader.download(specs) { progress ->
                    mutableState.value = ModelSetupState.Downloading(
                        progress.doneBytes, progress.totalBytes, progress.fileName,
                    )
                }
                mutableState.value = ModelSetupState.Ready
            } catch (cancelled: CancellationException) {
                mutableState.value = ModelSetupState.NeedsDownload(downloader.missingBytes(specs))
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = ModelSetupState.Failed(
                    error.message ?: "Не удалось скачать модели",
                    downloader.missingBytes(specs),
                )
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }
}
