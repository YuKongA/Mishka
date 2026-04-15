package top.yukonga.mishka.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.yukonga.mishka.data.database.SelectionDao
import top.yukonga.mishka.data.database.SelectionEntity
import top.yukonga.mishka.data.repository.MihomoRepository

data class ProxyGroupUi(
    val name: String = "",
    val type: String = "",
    val now: String = "",
    val all: List<String> = emptyList(),
    val delays: Map<String, Int> = emptyMap(),
    val nodeTypes: Map<String, String> = emptyMap(),
    val icon: String = "",
)

data class ProxyUiState(
    val groups: List<ProxyGroupUi> = emptyList(),
    val isLoading: Boolean = false,
    val isTesting: Boolean = false,
    val error: String = "",
)

class ProxyViewModel(
    private val selectionDao: SelectionDao? = null,
    private val getActiveUuid: () -> String? = { null },
) : ViewModel() {

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
            val groupsResult = repo.getGroups()
            val proxiesResult = repo.getProxies()

            groupsResult.onSuccess { groupsResponse ->
                val allProxies = proxiesResult.getOrNull()?.proxies ?: emptyMap()

                val globalGroup = groupsResponse.proxies.firstOrNull { it.name == "GLOBAL" }
                val orderMap = globalGroup?.all
                    ?.mapIndexed { index, name -> name to index }
                    ?.toMap() ?: emptyMap()

                val groups = groupsResponse.proxies
                    .filter { it.name != "GLOBAL" }
                    .sortedBy { orderMap[it.name] ?: Int.MAX_VALUE }
                    .map { node ->
                        val delays = mutableMapOf<String, Int>()
                        val nodeTypes = mutableMapOf<String, String>()
                        node.all.forEach { proxyName ->
                            val proxy = allProxies[proxyName]
                            val lastDelay = proxy?.history?.lastOrNull()?.delay
                            if (lastDelay != null && lastDelay > 0) {
                                delays[proxyName] = lastDelay
                            } else if (proxy != null && proxy.now.isNotEmpty()) {
                                val nowProxy = allProxies[proxy.now]
                                val nowDelay = nowProxy?.history?.lastOrNull()?.delay
                                if (nowDelay != null && nowDelay > 0) {
                                    delays[proxyName] = nowDelay
                                }
                            }
                            if (proxy != null && proxy.type.isNotEmpty()) {
                                nodeTypes[proxyName] = proxy.type
                            }
                        }
                        ProxyGroupUi(
                            name = node.name,
                            type = node.type,
                            now = node.now,
                            all = node.all,
                            delays = delays,
                            nodeTypes = nodeTypes,
                            icon = node.icon,
                        )
                    }
                _uiState.value = _uiState.value.copy(
                    groups = groups,
                    isLoading = false,
                )

                // 恢复已保存的代理组选择
                restoreSelections(repo, groups)
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
                // 保存选择到数据库
                saveSelection(group, proxy)
            }
        }
    }

    fun testGroupDelay(group: String) {
        val repo = repository ?: return
        _uiState.value = _uiState.value.copy(isTesting = true)

        viewModelScope.launch {
            repo.healthCheck(group)
            loadProxies()
            _uiState.value = _uiState.value.copy(isTesting = false)
        }
    }

    private suspend fun saveSelection(group: String, proxy: String) {
        val uuid = getActiveUuid() ?: return
        val dao = selectionDao ?: return
        dao.insert(SelectionEntity(uuid = uuid, proxy = group, selected = proxy))
    }

    private suspend fun restoreSelections(repo: MihomoRepository, groups: List<ProxyGroupUi>) {
        val uuid = getActiveUuid() ?: return
        val dao = selectionDao ?: return
        val selections = dao.queryByUUID(uuid)
        if (selections.isEmpty()) return

        val selectionMap = selections.associate { it.proxy to it.selected }
        val updatedGroups = groups.toMutableList()

        for ((index, group) in groups.withIndex()) {
            // 只恢复 Selector 类型的组
            if (group.type != "Selector") continue
            val saved = selectionMap[group.name] ?: continue
            if (saved == group.now) continue
            if (saved !in group.all) continue

            repo.selectProxy(group.name, saved).onSuccess {
                updatedGroups[index] = group.copy(now = saved)
            }
        }

        _uiState.value = _uiState.value.copy(groups = updatedGroups)
    }
}
