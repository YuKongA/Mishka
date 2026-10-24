package top.yukonga.mishka.viewmodel

import android.util.Log
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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.yukonga.mishka.data.database.SelectionDao
import top.yukonga.mishka.data.database.SelectionEntity
import top.yukonga.mishka.domain.model.ProxyNode
import top.yukonga.mishka.domain.repository.MihomoRepository
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.util.describe

@Immutable
data class ProxyGroupUi(
    val name: String = "",
    val type: String = "",
    val now: String = "",
    val all: ImmutableList<String> = persistentListOf(),
    val delays: ImmutableMap<String, Int> = persistentMapOf(),
    val nodeTypes: ImmutableMap<String, String> = persistentMapOf(),
    val icon: String = "",
    val fixed: String = "",
    val testUrl: String = "",
) {
    /** 对应 mihomo 的 `outboundgroup.SelectAble`；LoadBalance / Relay 不实现，PUT 返回 400 */
    val isSelectable: Boolean
        get() = isSelector ||
                type.equals("URLTest", ignoreCase = true) ||
                type.equals("Fallback", ignoreCase = true)

    /** 选中写 now；其余可选组写 fixed（now 仍是自动择优结果） */
    val isSelector: Boolean get() = type.equals("Selector", ignoreCase = true)

    val isFixed: Boolean get() = fixed.isNotEmpty()
}

@Immutable
data class ProxyUiState(
    val groups: ImmutableList<ProxyGroupUi> = persistentListOf(),
    val testingGroups: ImmutableSet<String> = persistentSetOf(),
    val testingNodes: ImmutableSet<String> = persistentSetOf(),
    val error: String = "",
    // mihomo 出站模式，小写；取不到配置时为空串
    val mode: String = "",
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

    // 节点每行 1 个铺满宽度，关闭时每行 2 个
    private val _singleColumn = MutableStateFlow(loadInitialSingleColumn())
    val singleColumn: StateFlow<Boolean> = _singleColumn.asStateFlow()

    private val _showGroupIcons = MutableStateFlow(
        storage?.getString(StorageKeys.PROXY_GROUP_SHOW_ICONS, "true") != "false"
    )
    val showGroupIcons: StateFlow<Boolean> = _showGroupIcons.asStateFlow()

    private var repository: MihomoRepository? = null

    // mihomo 重启切 client 时取消旧的 loadProxies 协程，防止其 HTTP 响应已读完但 UI 写回
    // 晚于新 client 的写入，把刚切走的旧订阅代理组覆盖回来
    private var loadJob: Job? = null

    /** 已对哪条连接恢复过保存的组选择，保证每条连接只推一次 */
    private var selectionsRestoredFor: MihomoRepository? = null

    // provider 节点名 → provider 名。/proxies 命名空间只含 runtime 节点，
    // provider 节点的延迟测试必须路由到 /providers/proxies/{provider}/{node}/healthcheck
    private var nodeProviderMap: Map<String, String> = emptyMap()

    fun updateSortOption(option: Int) {
        _sortOption.value = option
        storage?.putString(StorageKeys.PROXY_NODE_SORT_OPTION, option.toString())
    }

    private fun loadInitialSortOption(): Int =
        storage?.getString(StorageKeys.PROXY_NODE_SORT_OPTION, "0")?.toIntOrNull() ?: 0

    fun updateSingleColumn(enabled: Boolean) {
        _singleColumn.value = enabled
        storage?.putString(StorageKeys.PROXY_NODE_SINGLE_COLUMN, if (enabled) "true" else "false")
    }

    private fun loadInitialSingleColumn(): Boolean =
        storage?.getString(StorageKeys.PROXY_NODE_SINGLE_COLUMN, "false") == "true"

    fun updateShowGroupIcons(enabled: Boolean) {
        _showGroupIcons.value = enabled
        storage?.putString(StorageKeys.PROXY_GROUP_SHOW_ICONS, enabled.toString())
    }

    fun setRepository(repo: MihomoRepository?) {
        if (repository === repo) return
        loadJob?.cancel()
        repository = repo
        if (repo != null) {
            // 旧连接上的测速已无意义，标记留着就是永久转圈的 spinner
            _uiState.value = _uiState.value.copy(
                testingGroups = persistentSetOf(),
                testingNodes = persistentSetOf(),
            )
            loadProxies()
        } else {
            nodeProviderMap = emptyMap()
            _uiState.value = ProxyUiState()
        }
    }

    fun loadProxies() {
        val repo = repository ?: return
        _uiState.value = _uiState.value.copy(error = "")

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // 四个请求彼此无依赖，串行会把切 Tab 的刷新延迟叠成四个 RTT
            val groupsDeferred = async { repo.getGroups() }
            val proxiesDeferred = async { repo.getProxies() }
            val providersDeferred = async { repo.getProviders() }
            val configDeferred = async { repo.getConfig() }
            val groupsResult = groupsDeferred.await()
            val proxiesResult = proxiesDeferred.await()
            val providersResult = providersDeferred.await()
            val mode = configDeferred.await().getOrNull()?.mode?.lowercase().orEmpty()
            // 协程被 cancel 后 HTTP 响应仍可能已读完，二次校验 repo identity 防止把旧 client
            // 的结果写到当前 repo 已切换后的 UI
            if (repository !== repo) return@launch

            groupsResult.onSuccess { groupsResponse ->
                // /proxies 只含 runtime 节点（proxies: 段 + 代理组），proxy-provider 节点
                // 的类型与延迟 history 要从 /providers/proxies 合并（runtime 优先补缺）
                val runtimeProxies = proxiesResult.getOrNull()?.proxies ?: emptyMap()
                val allProxies = runtimeProxies.toMutableMap()
                val providerOf = mutableMapOf<String, String>()
                runtimeProxies.forEach { (name, node) ->
                    if (node.providerName.isNotEmpty()) {
                        providerOf[name] = node.providerName
                    }
                }
                providersResult.getOrNull()?.providers?.forEach { (providerName, provider) ->
                    provider.proxies.forEach { node ->
                        if (node.name !in runtimeProxies) {
                            allProxies.putIfAbsent(node.name, node)
                        }
                        providerOf.putIfAbsent(node.name, providerName)
                    }
                }
                nodeProviderMap = providerOf

                val globalGroup = groupsResponse.proxies.firstOrNull { it.name == GLOBAL_GROUP }
                val orderMap = globalGroup?.all?.withIndex()?.associate { it.value to it.index } ?: emptyMap()

                // 过滤 hidden 策略组；GLOBAL 同样遵循 hidden，但在全局模式下作为唯一生效出口强制保留
                val showGlobal = globalGroup != null && (!globalGroup.hidden || mode == MODE_GLOBAL)
                val orderedGroups = groupsResponse.proxies
                    .filter { it.name != GLOBAL_GROUP && !it.hidden }
                    .sortedBy { orderMap[it.name] ?: Int.MAX_VALUE }
                val arrangedGroups = when {
                    globalGroup == null || !showGlobal -> orderedGroups
                    mode == MODE_GLOBAL -> listOf(globalGroup) + orderedGroups
                    else -> orderedGroups + globalGroup
                }

                val groups = arrangedGroups
                    .map { node ->
                        val delays = mutableMapOf<String, Int>()
                        val nodeTypes = mutableMapOf<String, String>()
                        node.all.forEach { proxyName ->
                            val proxy = allProxies[proxyName]
                            val resolved = resolveDelay(proxyName, allProxies)
                            if (resolved != null) delays[proxyName] = resolved
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
                            fixed = node.fixed,
                            testUrl = node.testUrl,
                        )
                    }
                    .toPersistentList()
                _uiState.value = _uiState.value.copy(groups = groups, mode = mode)

                // 「恢复已保存选择」是每条连接做一次的事。挂在每次 loadProxies 上会让切代理
                // Tab、每次测速都把用户在其它客户端做的选择强推回去，还多出 N 次 PUT
                if (selectionsRestoredFor !== repo) {
                    selectionsRestoredFor = repo
                    restoreSelections(repo, groups)
                }
            }.onFailure {
                // 只存原因，前缀文案由渲染方本地化；describe 兜住 Ktor 无参异常的 null message
                Log.w(TAG, "loadProxies failed", it)
                _uiState.value = _uiState.value.copy(error = it.describe())
            }
        }
    }

    fun selectProxy(group: String, proxy: String) {
        val repo = repository ?: return
        val target = groupOf(group) ?: return
        // 不可选组 PUT 必定 400，提前挡下省一个 RTT
        if (!target.isSelectable) return
        viewModelScope.launch {
            repo.selectProxy(group, proxy).onSuccess {
                if (repository !== repo) return@onSuccess
                _uiState.value = _uiState.value.copy(
                    groups = _uiState.value.groups
                        .map {
                            when {
                                it.name != group -> it
                                // 非 Selector 组的选中即固定，两个字段同步写
                                it.isSelector -> it.copy(now = proxy)
                                else -> it.copy(now = proxy, fixed = proxy)
                            }
                        }
                        .toPersistentList()
                )
                saveSelection(group, proxy)
            }
        }
    }

    /**
     * 解除固定。selections 记录必须一并删，它是 restoreSelections 的数据源，
     * 留着会在下次连接时把钉子推回去
     */
    fun unfixProxy(group: String) {
        val repo = repository ?: return
        viewModelScope.launch {
            repo.unfixProxy(group).onSuccess {
                // 校验先于 DB 写：clearSelection 按当前 uuid 定位，repo 切走后会误删新订阅
                if (repository !== repo) return@launch
                clearSelection(group)
                // now 由 mihomo 重新择优，本地推算不出
                loadProxies()
            }
        }
    }

    fun testGroupDelay(group: String) {
        val repo = repository ?: return
        if (group in _uiState.value.testingGroups) return
        val groupUi = groupOf(group)
        val nodes = groupUi?.all ?: return
        if (nodes.isEmpty()) return
        val groupTestUrl = groupUi.testUrl.takeIf { it.isNotEmpty() } ?: "http://www.gstatic.com/generate_204"
        val currentFixed = groupUi.fixed.takeIf { it.isNotEmpty() && !groupUi.isSelector }
        _uiState.value = _uiState.value.copy(
            testingGroups = (_uiState.value.testingGroups + group).toPersistentSet(),
        )

        viewModelScope.launch {
            try {
                // 调内核 /group/{name}/delay 单次并发测完整组，防串行 HTTP 阻塞
                val delayResult = repo.getGroupDelay(group, groupTestUrl, 5000)
                if (repository !== repo) return@launch
                val delayMap = delayResult.getOrNull() ?: emptyMap()

                val updatedGroups = _uiState.value.groups.map { g ->
                    if (g.name == group) {
                        val newDelays = g.delays.toMutableMap()
                        g.all.forEach { nodeName ->
                            val d = delayMap[nodeName]
                            if (d != null && d > 0) {
                                newDelays[nodeName] = d
                            } else if (delayResult.isSuccess) {
                                // 未返回或 0 表示超时/失败
                                newDelays[nodeName] = -1
                            }
                        }
                        g.copy(delays = newDelays.toPersistentMap())
                    } else {
                        val affected = g.all.filter { it in delayMap }
                        if (affected.isNotEmpty()) {
                            val newDelays = g.delays.toMutableMap()
                            affected.forEach { nodeName ->
                                val d = delayMap[nodeName]
                                if (d != null && d > 0) newDelays[nodeName] = d
                            }
                            g.copy(delays = newDelays.toPersistentMap())
                        } else {
                            g
                        }
                    }
                }.toPersistentList()

                _uiState.value = _uiState.value.copy(groups = updatedGroups)

                // 非 Selector 组恢复固定的节点
                if (currentFixed != null) {
                    repo.selectProxy(group, currentFixed)
                }
            } finally {
                _uiState.value = _uiState.value.copy(
                    testingGroups = (_uiState.value.testingGroups - group).toPersistentSet(),
                )
                // 返回值不含子组名，重拉后才能同步父组延迟。
                if (repository === repo) loadProxies()
            }
        }
    }

    fun testNodeDelay(nodeName: String) {
        val repo = repository ?: return
        if (nodeName in _uiState.value.testingNodes) return
        _uiState.value = _uiState.value.copy(
            testingNodes = (_uiState.value.testingNodes + nodeName).toPersistentSet(),
        )

        viewModelScope.launch {
            try {
                val provider = nodeProviderMap[nodeName]
                val result = if (provider != null) {
                    repo.getProviderProxyDelay(provider, nodeName)
                } else {
                    repo.getProxyDelay(nodeName)
                }
                if (repository !== repo) return@launch
                val delayValue = result.getOrNull()?.delay?.takeIf { it > 0 } ?: -1

                // 单节点就地更新延迟，不全量重拉配置
                val updatedGroups = _uiState.value.groups.map { group ->
                    if (nodeName in group.all) {
                        val newDelays = group.delays.toMutableMap()
                        newDelays[nodeName] = delayValue
                        group.copy(delays = newDelays.toPersistentMap())
                    } else {
                        group
                    }
                }.toPersistentList()

                _uiState.value = _uiState.value.copy(groups = updatedGroups)
            } finally {
                _uiState.value = _uiState.value.copy(
                    testingNodes = (_uiState.value.testingNodes - nodeName).toPersistentSet(),
                )
            }
        }
    }

    private suspend fun saveSelection(group: String, proxy: String) {
        val uuid = getActiveUuid() ?: return
        val dao = selectionDao ?: return
        dao.insert(SelectionEntity(uuid = uuid, proxy = group, selected = proxy))
    }

    private fun groupOf(name: String): ProxyGroupUi? =
        _uiState.value.groups.firstOrNull { it.name == name }

    private suspend fun clearSelection(group: String) {
        val uuid = getActiveUuid() ?: return
        val dao = selectionDao ?: return
        dao.remove(uuid = uuid, proxy = group)
    }

    private suspend fun restoreSelections(repo: MihomoRepository, groups: ImmutableList<ProxyGroupUi>) {
        val uuid = getActiveUuid() ?: return
        val dao = selectionDao ?: return
        val selections = dao.queryByUUID(uuid)
        if (selections.isEmpty()) return

        val selectionMap = selections.associate { it.proxy to it.selected }
        val updatedGroups = groups.toMutableList()

        for ((index, group) in groups.withIndex()) {
            if (!group.isSelectable) continue
            val saved = selectionMap[group.name] ?: continue
            if (saved !in group.all) continue
            // 非 Selector 组必须比 fixed：其 now 是择优结果，恰好等于 saved 时
            // 跳过会让该组停在未固定态
            if (saved == (if (group.isSelector) group.now else group.fixed)) continue

            repo.selectProxy(group.name, saved).onSuccess {
                updatedGroups[index] = if (group.isSelector) {
                    group.copy(now = saved)
                } else {
                    group.copy(now = saved, fixed = saved)
                }
            }
        }

        // 期间跑过 Room 查询与若干 selectProxy，repo 可能已被换掉；loadJob.cancel 之外
        // 再校一次身份，否则旧连接的恢复结果会覆盖新订阅的组
        if (repository !== repo) return
        _uiState.value = _uiState.value.copy(groups = updatedGroups.toPersistentList())
    }

    companion object {
        const val GLOBAL_GROUP = "GLOBAL"
        const val MODE_GLOBAL = "global"
        const val MODE_DIRECT = "direct"
        private const val TAG = "ProxyViewModel"
    }

    /** 子组沿 now 取当前出口；步数上限防配置环。 */
    private fun resolveDelay(name: String, allProxies: Map<String, ProxyNode>): Int? {
        var current = name
        var remaining = allProxies.size + 1
        while (remaining-- > 0) {
            val proxy = allProxies[current] ?: return null
            if (proxy.now.isNotEmpty()) {
                current = proxy.now
                continue
            }
            val delay = proxy.history.lastOrNull()?.delay ?: return null
            return if (delay > 0) delay else -1
        }
        return null
    }
}
