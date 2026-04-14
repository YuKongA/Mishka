package top.yukonga.mishka.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.yukonga.mishka.data.repository.MihomoRepository

data class ProxyGroupUi(
    val name: String = "",
    val type: String = "",
    val now: String = "",
    val all: List<String> = emptyList(),
    val delays: Map<String, Int> = emptyMap(),
)

data class ProxyUiState(
    val groups: List<ProxyGroupUi> = emptyList(),
    val isLoading: Boolean = false,
    val isTesting: Boolean = false,
    val error: String = "",
)

class ProxyViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ProxyUiState())
    val uiState: StateFlow<ProxyUiState> = _uiState.asStateFlow()

    private var repository: MihomoRepository? = null

    fun setRepository(repo: MihomoRepository?) {
        repository = repo
        if (repo != null) {
            loadProxies()
        } else {
            _uiState.value = ProxyUiState()
        }
    }

    fun loadProxies() {
        val repo = repository ?: return
        _uiState.value = _uiState.value.copy(isLoading = true, error = "")

        viewModelScope.launch {
            // 同时获取组信息和所有代理详情（含延迟历史）
            val groupsResult = repo.getGroups()
            val proxiesResult = repo.getProxies()

            groupsResult.onSuccess { groupsResponse ->
                // 从 /proxies 获取每个节点的最新延迟
                val allProxies = proxiesResult.getOrNull()?.proxies ?: emptyMap()

                // 从 GLOBAL 组的 all 字段获取配置文件中的原始顺序
                val globalGroup = groupsResponse.proxies.firstOrNull { it.name == "GLOBAL" }
                val orderMap = globalGroup?.all
                    ?.mapIndexed { index, name -> name to index }
                    ?.toMap() ?: emptyMap()

                val groups = groupsResponse.proxies
                    .filter { it.name != "GLOBAL" }
                    .sortedBy { orderMap[it.name] ?: Int.MAX_VALUE }
                    .map { node ->
                        val delays = mutableMapOf<String, Int>()
                        node.all.forEach { proxyName ->
                            val proxy = allProxies[proxyName]
                            val lastDelay = proxy?.history?.lastOrNull()?.delay
                            if (lastDelay != null && lastDelay > 0) {
                                delays[proxyName] = lastDelay
                            }
                        }
                        ProxyGroupUi(
                            name = node.name,
                            type = node.type,
                            now = node.now,
                            all = node.all,
                            delays = delays,
                        )
                    }
                _uiState.value = _uiState.value.copy(
                    groups = groups,
                    isLoading = false,
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "加载失败: ${it.message}",
                )
            }
        }
    }

    fun selectProxy(group: String, proxy: String) {
        val repo = repository ?: return
        viewModelScope.launch {
            repo.selectProxy(group, proxy).onSuccess {
                _uiState.value = _uiState.value.copy(
                    groups = _uiState.value.groups.map {
                        if (it.name == group) it.copy(now = proxy) else it
                    }
                )
            }
        }
    }

    fun testGroupDelay(group: String) {
        val repo = repository ?: return
        _uiState.value = _uiState.value.copy(isTesting = true)

        viewModelScope.launch {
            // healthcheck 触发组内所有节点测速
            // url-test/fallback 组会自动更新最优节点（now 字段）
            repo.healthCheck(group)
            // 重新加载（含最新延迟和自动选择结果）
            loadProxies()
            _uiState.value = _uiState.value.copy(isTesting = false)
        }
    }
}
