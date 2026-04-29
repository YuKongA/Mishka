package top.yukonga.mishka.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import top.yukonga.mishka.data.repository.OverrideJsonStore

/**
 * ExternalControlScreen 的 ViewModel：mihomo HTTP API 监听地址 + Bearer secret。
 */
class ExternalControlViewModel(
    private val store: OverrideJsonStore,
) : ViewModel() {

    val state: StateFlow<ExternalControlUiState> = store.state
        .map { ExternalControlUiState(externalController = it.externalController, secret = it.secret) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = store.state.value.let {
                ExternalControlUiState(externalController = it.externalController, secret = it.secret)
            },
        )

    fun setExternalController(value: String?) {
        store.update { it.copy(externalController = value) }
    }

    fun setSecret(value: String?) {
        store.update { it.copy(secret = value) }
    }
}

@Immutable
data class ExternalControlUiState(
    val externalController: String? = null,
    val secret: String? = null,
)
