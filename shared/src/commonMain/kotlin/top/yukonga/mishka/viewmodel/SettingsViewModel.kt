package top.yukonga.mishka.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.yukonga.mishka.data.model.ConfigPatch
import top.yukonga.mishka.data.model.MihomoConfig
import top.yukonga.mishka.data.repository.MihomoRepository

data class SettingsUiState(
    val config: MihomoConfig? = null,
    val isLoading: Boolean = false,
    val error: String = "",
)

class SettingsViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var repository: MihomoRepository? = null

    fun setRepository(repo: MihomoRepository?) {
        repository = repo
        if (repo != null) loadConfig()
    }

    fun loadConfig() {
        val repo = repository ?: return
        _uiState.value = _uiState.value.copy(isLoading = true, error = "")

        viewModelScope.launch {
            repo.getConfig()
                .onSuccess { config ->
                    _uiState.value = _uiState.value.copy(
                        config = config,
                        isLoading = false,
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "加载失败: ${it.message}",
                    )
                }
        }
    }

    fun patchConfig(patch: ConfigPatch) {
        val repo = repository ?: return

        viewModelScope.launch {
            repo.patchConfig(patch)
                .onSuccess { loadConfig() }
                .onFailure {
                    _uiState.value = _uiState.value.copy(error = "修改失败: ${it.message}")
                }
        }
    }
}
