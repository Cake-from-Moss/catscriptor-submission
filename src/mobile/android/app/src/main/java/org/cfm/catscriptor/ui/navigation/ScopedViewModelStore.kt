package org.cfm.catscriptor.ui.navigation

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Хранилища ViewModel для экранов, которые открываются и закрываются (расшифровка, лист экспорта).
 * Без этого их ViewModel жили бы в хранилище Activity до её закрытия и держали подписки
 * на репозиторий для каждой когда-либо открытой записи.
 */
class ScopedViewModelStores : ViewModel() {
    private val stores = mutableMapOf<String, ViewModelStore>()

    fun storeFor(key: String): ViewModelStore = stores.getOrPut(key) { ViewModelStore() }

    fun clear(key: String) {
        stores.remove(key)?.clear()
    }

    override fun onCleared() {
        stores.values.forEach(ViewModelStore::clear)
        stores.clear()
    }
}

/**
 * Даёт [content] собственное хранилище ViewModel по [key]. Хранилище переживает смену конфигурации
 * и очищается, когда экран уходит из композиции по-настоящему.
 */
@Composable
fun ScopedViewModels(key: String, content: @Composable () -> Unit) {
    val activity = LocalActivity.current
    val stores = viewModel<ScopedViewModelStores>()
    val owner = remember(stores, key) {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = stores.storeFor(key)
        }
    }
    DisposableEffect(stores, key) {
        onDispose {
            // При повороте экрана композиция тоже уходит, но состояние экрана нужно сохранить.
            if (activity?.isChangingConfigurations != true) stores.clear(key)
        }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}
