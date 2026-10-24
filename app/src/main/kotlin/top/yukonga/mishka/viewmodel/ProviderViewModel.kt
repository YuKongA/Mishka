package top.yukonga.mishka.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.yukonga.mishka.domain.repository.MihomoRepository
import top.yukonga.mishka.util.describe

data class ProviderItemUi(
    val name: String,
    // 技术类型原值（proxy provider 取 type，rule provider 取 vehicleType）；
    // 展示成「代理(…)/规则(…)」由屏幕按 isRuleProvider 本地化拼装
    val type: String,
    val vehicleType: String,
    val updatedAt: String,
    val isRuleProvider: Boolean,
)

@Immutable
data class ProviderErrorKey(
    val name: String,
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

@Immutable
data class ProviderUiState(
    val providers: ImmutableList<ProviderItemUi> = persistentListOf(),
    val isLoading: Boolean = false,
    val providerErrors: ImmutableMap<ProviderErrorKey, String> = persistentMapOf(),
    val refresh: RefreshProgress? = null,
)

class ProviderViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ProviderUiState())
    val uiState: StateFlow<ProviderUiState> = _uiState.asStateFlow()

    private var repository: MihomoRepository? = null

    // 切 client 时必须取消，理由见 ProxyViewModel.loadJob
    private var loadJob: Job? = null
    private var refreshJob: Job? = null

    fun setRepository(repo: MihomoRepository?) {
        if (repository === repo) return
        loadJob?.cancel()
        refreshJob?.cancel()
        repository = repo
        if (repo != null) {
            _uiState.value = ProviderUiState(isLoading = true)
            loadProviders()
        } else {
            _uiState.value = ProviderUiState()
        }
    }

    fun loadProviders() {
        val repo = repository ?: return
        _uiState.update { it.copy(isLoading = true) }

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val items = mutableListOf<ProviderItemUi>()

            repo.getProviders().onSuccess { response ->
                response.providers.values
                    .filter { it.vehicleType != "Compatible" }
                    .forEach { info ->
                        items.add(
                            ProviderItemUi(
                                name = info.name,
                                type = info.type,
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
                                type = info.vehicleType,
                                vehicleType = info.vehicleType,
                                updatedAt = info.updatedAt,
                                isRuleProvider = true,
                            )
                        )
                    }
            }

            // repo 已被切换则丢弃 in-flight 响应，避免覆盖新订阅的 provider 列表
            if (repository !== repo) return@launch
            // 代理 provider 排在规则 provider 前面；各自组内按 name 升序
            val liveKeys = items.map { ProviderErrorKey(it.name, it.isRuleProvider) }.toSet()
            _uiState.update { state ->
                state.copy(
                    providers = items.sortedWith(compareBy({ it.isRuleProvider }, { it.name })).toPersistentList(),
                    isLoading = false,
                    // 修剪已从列表消失的 provider 的错误键，防止无对应行的错误永久滞留
                    providerErrors = state.providerErrors.filterKeys { it in liveKeys }.toPersistentMap(),
                )
            }
        }
    }

    fun updateProvider(name: String, isRuleProvider: Boolean) {
        val repo = repository ?: return
        if (_uiState.value.refresh != null) return // 已有刷新进行中，忽略重复点
        val errorKey = ProviderErrorKey(name, isRuleProvider)

        _uiState.update {
            it.copy(
                refresh = RefreshProgress(0, 1, singleName = name),
                providerErrors = it.providerErrors.toPersistentMap().removing(errorKey),
            )
        }

        refreshJob = viewModelScope.launch {
            val result = if (isRuleProvider) repo.updateRuleProvider(name) else repo.updateProvider(name)
            if (repository !== repo) return@launch
            val error = result.exceptionOrNull()?.describe()
            _uiState.update {
                it.copy(
                    refresh = null,
                    providerErrors = if (error == null) {
                        it.providerErrors.toPersistentMap().removing(errorKey)
                    } else {
                        it.providerErrors.toPersistentMap().putting(errorKey, error)
                    },
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

        _uiState.update {
            it.copy(
                refresh = RefreshProgress(0, snapshot.size),
                providerErrors = persistentMapOf(),
            )
        }

        refreshJob = viewModelScope.launch {
            // 并发刷新；每完成一个原子推进 completed 计数（MutableStateFlow.update 内部 CAS 保证正确性）
            snapshot.map { provider ->
                async {
                    val res = if (provider.isRuleProvider) repo.updateRuleProvider(provider.name)
                    else repo.updateProvider(provider.name)
                    if (repository !== repo) return@async
                    val error = res.exceptionOrNull()?.describe()
                    val errorKey = ProviderErrorKey(provider.name, provider.isRuleProvider)
                    _uiState.update { state ->
                        val cur = state.refresh ?: return@update state
                        state.copy(
                            refresh = cur.copy(completed = cur.completed + 1),
                            providerErrors = if (error == null) {
                                state.providerErrors.toPersistentMap().removing(errorKey)
                            } else {
                                state.providerErrors.toPersistentMap().putting(errorKey, error)
                            },
                        )
                    }
                }
            }.awaitAll()

            if (repository !== repo) return@launch
            _uiState.update {
                it.copy(
                    refresh = null,
                )
            }
            loadProviders()
        }
    }
}
