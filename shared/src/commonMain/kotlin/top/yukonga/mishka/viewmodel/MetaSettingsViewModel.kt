package top.yukonga.mishka.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import top.yukonga.mishka.data.model.ConfigurationOverride
import top.yukonga.mishka.data.model.SnifferOverride
import top.yukonga.mishka.data.repository.OverrideJsonStore

/**
 * MetaSettingsScreen 的 ViewModel：统一延迟 / Geodata / TCP 并发 / 进程查找模式 / 嗅探器。
 */
class MetaSettingsViewModel(
    private val store: OverrideJsonStore,
) : ViewModel() {

    val state: StateFlow<MetaSettingsUiState> = store.state
        .map { it.toMetaUi() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = store.state.value.toMetaUi(),
        )

    fun update(transform: (ConfigurationOverride) -> ConfigurationOverride) {
        store.update(transform)
    }

    fun updateSniffer(transform: (SnifferOverride) -> SnifferOverride) {
        store.update { state ->
            val current = state.sniffer ?: SnifferOverride()
            val next = transform(current)
            val allNull = next == SnifferOverride()
            state.copy(sniffer = if (allNull) null else next)
        }
    }
}

@Immutable
data class MetaSettingsUiState(
    val unifiedDelay: Boolean? = null,
    val geodataMode: Boolean? = null,
    val tcpConcurrent: Boolean? = null,
    val findProcessMode: String? = null,
    val sniffer: SnifferOverride? = null,
)

private fun ConfigurationOverride.toMetaUi() = MetaSettingsUiState(
    unifiedDelay = unifiedDelay,
    geodataMode = geodataMode,
    tcpConcurrent = tcpConcurrent,
    findProcessMode = findProcessMode,
    sniffer = sniffer,
)
