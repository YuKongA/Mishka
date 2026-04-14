package top.yukonga.mishka.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.yukonga.mishka.data.repository.MihomoRepository

data class ProviderItemUi(
    val name: String,
    val type: String,       // "proxy" 或 "rule"
    val vehicleType: String,
    val updatedAt: String,
    val isUpdating: Boolean = false,
)

data class ProviderUiState(
    val providers: List<ProviderItemUi> = emptyList(),
    val isLoading: Boolean = false,
    val error: String = "",
)

class ProviderViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ProviderUiState())
    val uiState: StateFlow<ProviderUiState> = _uiState.asStateFlow()

    private var repository: MihomoRepository? = null

    fun setRepository(repo: MihomoRepository?) {
        repository = repo
        if (repo != null) {
            loadProviders()
        } else {
            _uiState.value = ProviderUiState()
        }
    }

    fun loadProviders() {
        val repo = repository ?: return
        _uiState.value = _uiState.value.copy(isLoading = true, error = "")

        viewModelScope.launch {
            val items = mutableListOf<ProviderItemUi>()

            // 加载代理 provider
            repo.getProviders().onSuccess { response ->
                response.providers.values.forEach { info ->
                    items.add(
                        ProviderItemUi(
                            name = info.name,
                            type = "代理(${info.type})",
                            vehicleType = info.vehicleType,
                            updatedAt = info.updatedAt,
                        )
                    )
                }
            }

            // 加载规则 provider
            repo.getRuleProviders().onSuccess { response ->
                response.providers.values.forEach { info ->
                    items.add(
                        ProviderItemUi(
                            name = info.name,
                            type = "规则(${info.vehicleType})",
                            vehicleType = info.vehicleType,
                            updatedAt = info.updatedAt,
                        )
                    )
                }
            }

            _uiState.value = _uiState.value.copy(
                providers = items.sortedBy { it.name },
                isLoading = false,
            )
        }
    }

    fun updateProvider(name: String, isRuleProvider: Boolean) {
        val repo = repository ?: return

        // 标记正在更新
        _uiState.value = _uiState.value.copy(
            providers = _uiState.value.providers.map {
                if (it.name == name) it.copy(isUpdating = true) else it
            }
        )

        viewModelScope.launch {
            val result = if (isRuleProvider) {
                repo.updateRuleProvider(name)
            } else {
                repo.updateProvider(name)
            }

            result.onSuccess {
                loadProviders() // 重新加载获取最新 updatedAt
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    error = "更新 $name 失败: ${it.message}",
                    providers = _uiState.value.providers.map { p ->
                        if (p.name == name) p.copy(isUpdating = false) else p
                    },
                )
            }
        }
    }

    fun updateAll() {
        _uiState.value.providers.forEach { provider ->
            val isRule = provider.type.startsWith("规则")
            updateProvider(provider.name, isRule)
        }
    }
}
