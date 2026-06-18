package top.yukonga.mishka.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.yukonga.mishka.data.database.SelectionDao
import top.yukonga.mishka.data.database.SelectionEntity
import top.yukonga.mishka.data.repository.MihomoRepository
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys

@Immutable
data class ProxyGroupUi(
    val name: String = "",
    val type: String = "",
    val now: String = "",
    val all: ImmutableList<String> = persistentListOf(),
    val delays: ImmutableMap<String, Int> = persistentMapOf(),
    val nodeTypes: ImmutableMap<String, String> = persistentMapOf(),
    val icon: String = "",
)

@Immutable
data class ProxyUiState(
    val groups: ImmutableList<ProxyGroupUi> = persistentListOf(),
    val testingGroups: ImmutableSet<String> = persistentSetOf(),
    val testingNodes: ImmutableSet<String> = persistentSetOf(),
    val error: String = "",
)

class ProxyViewModel(
    private val selectionDao: SelectionDao? = null,
    private val getActiveUuid: () -> String? = { null },
    private val storage: PlatformStorage? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProxyUiState())
    val uiState: StateFlow<ProxyUiState> = _uiState.asStateFlow()

    // 节点排序状态：sortKeyIndex * 2 + (if reverse 1 else 0)
    // 0/1=默认 升/降，2/3=名称 升/降，4/5=延迟 升/降
    private val _sortOption = MutableStateFlow(loadInitialSortOption())
    val sortOption: StateFlow<Int> = _sortOption.asStateFlow()

    private var repository: MihomoRepository? = null

    // mihomo 重启切 client 时取消旧的 loadProxies 协程，防止其 HTTP 响应已读完但 UI 写回
    // 晚于新 client 的写入，把刚切走的旧订阅代理组覆盖回来
    private var loadJob: Job? = null

    fun updateSortOption(option: Int) {
        _sortOption.value = option
        storage?.putString(StorageKeys.PROXY_NODE_SORT_OPTION, option.toString())
    }

    private fun loadInitialSortOption(): Int =
        storage?.getString(StorageKeys.PROXY_NODE_SORT_OPTION, "0")?.toIntOrNull() ?: 0

    fun setRepository(repo: MihomoRepository?) {
        loadJob?.cancel()
        repository = repo
        if (repo != null) {
            loadProxies()
        } else {
            _uiState.value = ProxyUiState()
        }
    }

    fun loadProxies() {
        val repo = repository ?: return
        _uiState.value = _uiState.value.copy(error = "")

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val groupsResult = repo.getGroups()
            val proxiesResult = repo.getProxies()
            // 协程被 cancel 后 HTTP 响应仍可能已读完，二次校验 repo identity 防止把旧 client
            // 的结果写到当前 repo 已切换后的 UI
            if (repository !== repo) return@launch

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
                            } else if (lastDelay == 0) {
                                // delay=0 表示 healthcheck 超时/失败，映射为 -1 让 UI 显示"超时"
                                delays[proxyName] = -1
                            } else if (proxy != null && proxy.now.isNotEmpty()) {
                                val nowProxy = allProxies[proxy.now]
                                val nowDelay = nowProxy?.history?.lastOrNull()?.delay
                                if (nowDelay != null && nowDelay > 0) {
                                    delays[proxyName] = nowDelay
                                } else if (nowDelay == 0) {
                                    delays[proxyName] = -1
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
                            all = node.all.toPersistentList(),
                            delays = delays.toPersistentMap(),
                            nodeTypes = nodeTypes.toPersistentMap(),
                            icon = node.icon,
                        )
                    }
                    .toPersistentList()
                _uiState.value = _uiState.value.copy(groups = groups)

                // 恢复已保存的代理组选择
                restoreSelections(repo, groups)
            }.onFailure {
                _uiState.value = _uiState.value.copy(error = "加载失败: ${it.message}")
            }
        }
    }

    fun selectProxy(group: String, proxy: String) {
        val repo = repository ?: return
        viewModelScope.launch {
            repo.selectProxy(group, proxy).onSuccess {
                if (repository !== repo) return@onSuccess
                _uiState.value = _uiState.value.copy(
                    groups = _uiState.value.groups
                        .map { if (it.name == group) it.copy(now = proxy) else it }
                        .toPersistentList()
                )
                // 保存选择到数据库
                saveSelection(group, proxy)
            }
        }
    }

    fun testGroupDelay(group: String) {
        val repo = repository ?: return
        if (group in _uiState.value.testingGroups) return
        _uiState.value = _uiState.value.copy(
            testingGroups = (_uiState.value.testingGroups + group).toPersistentSet(),
        )

        viewModelScope.launch {
            // mihomo /group/{name}/delay 测全部节点延迟，结果会自然写入 history.delay
            repo.testGroupDelay(group)
            if (repository !== repo) return@launch
            loadProxies()
            _uiState.value = _uiState.value.copy(
                testingGroups = (_uiState.value.testingGroups - group).toPersistentSet(),
            )
        }
    }

    fun testNodeDelay(nodeName: String) {
        val repo = repository ?: return
        if (nodeName in _uiState.value.testingNodes) return
        _uiState.value = _uiState.value.copy(
            testingNodes = (_uiState.value.testingNodes + nodeName).toPersistentSet(),
        )

        viewModelScope.launch {
            // mihomo /proxies/{name}/delay 把结果写入全局 history.delay，
            // loadProxies 再从 history 读回并分发到所有引用该节点的组，保证跨组延迟一致
            repo.getProxyDelay(nodeName)
            if (repository !== repo) {
                _uiState.value = _uiState.value.copy(
                    testingNodes = (_uiState.value.testingNodes - nodeName).toPersistentSet(),
                )
                return@launch
            }
            loadProxies()
            _uiState.value = _uiState.value.copy(
                testingNodes = (_uiState.value.testingNodes - nodeName).toPersistentSet(),
            )
        }
    }

    private suspend fun saveSelection(group: String, proxy: String) {
        val uuid = getActiveUuid() ?: return
        val dao = selectionDao ?: return
        dao.insert(SelectionEntity(uuid = uuid, proxy = group, selected = proxy))
    }

    private suspend fun restoreSelections(repo: MihomoRepository, groups: ImmutableList<ProxyGroupUi>) {
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

        _uiState.value = _uiState.value.copy(groups = updatedGroups.toPersistentList())
    }
}
