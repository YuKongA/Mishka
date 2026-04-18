package top.yukonga.mishka.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.yukonga.mishka.data.repository.MihomoRepository
import top.yukonga.mishka.util.describe

data class ProviderItemUi(
    val name: String,
    val type: String,       // 用户可见的类型字符串，如"代理(http)" / "规则(Domain)"
    val vehicleType: String,
    val updatedAt: String,
    val isRuleProvider: Boolean,
)

/**
 * 单次刷新会话的进度。null 表示当前没有进行中的刷新。
 * [singleName] 非 null 时为单条刷新（dialog 显示 "正在更新 xxx…"），null 时为 updateAll
 * （dialog 显示 "done / total"）。
 */
data class RefreshProgress(
    val completed: Int,
    val total: Int,
    val singleName: String? = null,
)

data class ProviderUiState(
    val providers: List<ProviderItemUi> = emptyList(),
    val isLoading: Boolean = false,
    val error: String = "",
    val refresh: RefreshProgress? = null,
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
        _uiState.update { it.copy(isLoading = true, error = "") }

        viewModelScope.launch {
            val items = mutableListOf<ProviderItemUi>()

            repo.getProviders().onSuccess { response ->
                response.providers.values
                    .filter { it.vehicleType != "Compatible" }
                    .forEach { info ->
                        items.add(
                            ProviderItemUi(
                                name = info.name,
                                type = "代理(${info.type})",
                                vehicleType = info.vehicleType,
                                updatedAt = info.updatedAt,
                                isRuleProvider = false,
                            )
                        )
                    }
            }

            repo.getRuleProviders().onSuccess { response ->
                response.providers.values
                    .filter { it.vehicleType != "Compatible" }
                    .forEach { info ->
                        items.add(
                            ProviderItemUi(
                                name = info.name,
                                type = "规则(${info.vehicleType})",
                                vehicleType = info.vehicleType,
                                updatedAt = info.updatedAt,
                                isRuleProvider = true,
                            )
                        )
                    }
            }

            // 代理 provider 排在规则 provider 前面；各自组内按 name 升序
            _uiState.update {
                it.copy(
                    providers = items.sortedWith(compareBy({ it.isRuleProvider }, { it.name })),
                    isLoading = false,
                )
            }
        }
    }

    fun updateProvider(name: String, isRuleProvider: Boolean) {
        val repo = repository ?: return
        if (_uiState.value.refresh != null) return // 已有刷新进行中，忽略重复点

        _uiState.update { it.copy(refresh = RefreshProgress(0, 1, singleName = name), error = "") }

        viewModelScope.launch {
            val result = if (isRuleProvider) repo.updateRuleProvider(name) else repo.updateProvider(name)
            _uiState.update {
                it.copy(
                    refresh = null,
                    error = result.exceptionOrNull()?.let { e -> "更新 $name 失败: ${e.describe()}" }.orEmpty(),
                )
            }
            if (result.isSuccess) loadProviders()
        }
    }

    fun updateAll() {
        val repo = repository ?: return
        val snapshot = _uiState.value.providers
        if (snapshot.isEmpty()) return
        if (_uiState.value.refresh != null) return

        _uiState.update { it.copy(refresh = RefreshProgress(0, snapshot.size), error = "") }

        viewModelScope.launch {
            val failures = mutableListOf<String>()
            // 并发刷新；每完成一个原子推进 completed 计数（MutableStateFlow.update 内部 CAS 保证正确性）
            snapshot.map { provider ->
                async {
                    val res = if (provider.isRuleProvider) repo.updateRuleProvider(provider.name)
                    else repo.updateProvider(provider.name)
                    res.exceptionOrNull()?.let { e ->
                        synchronized(failures) { failures += "${provider.name}: ${e.describe()}" }
                    }
                    _uiState.update { state ->
                        val cur = state.refresh ?: return@update state
                        state.copy(refresh = cur.copy(completed = cur.completed + 1))
                    }
                }
            }.awaitAll()

            _uiState.update {
                it.copy(
                    refresh = null,
                    error = if (failures.isEmpty()) "" else failures.joinToString("\n"),
                )
            }
            loadProviders()
        }
    }
}
