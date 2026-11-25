package top.yukonga.mishka.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.yukonga.mishka.platform.AppInfo
import top.yukonga.mishka.platform.AppListProvider
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.platform.ProxyState
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.util.SearchMatcher
import top.yukonga.mishka.util.SearchQuery

enum class AppProxyMode { AllowAll, AllowSelected, DenySelected }

@Immutable
data class AppProxyUiState(
    val apps: ImmutableList<AppInfo> = persistentListOf(),
    val selectedPackages: PersistentSet<String> = persistentSetOf(),
    val mode: AppProxyMode = AppProxyMode.AllowAll,
    val searchQuery: String = "",
    val showSystemApps: Boolean = false,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
)

class AppProxyViewModel(
    private val storage: PlatformStorage,
    private val appListProvider: AppListProvider,
    private val serviceController: ProxyServiceController? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppProxyUiState())
    val uiState: StateFlow<AppProxyUiState> = _uiState.asStateFlow()

    // 进入页面时的快照，用于检测配置是否变更
    private var initialMode: AppProxyMode = AppProxyMode.AllowAll
    private var initialPackages: Set<String> = persistentSetOf()

    /** 排序锚点：初始勾选集合，下拉刷新时才同步当前勾选，避免勾选时列表抖动。 */
    private val _sortAnchor = MutableStateFlow<Set<String>>(emptySet())

    private var appsLoadJob: Job? = null

    /** 列表重算的输入。勾选态刻意不在其中，故 toggle 不重跑过滤排序 */
    private data class ListInput(
        val apps: ImmutableList<AppInfo>,
        val query: String,
        val showSystemApps: Boolean,
    )

    /**
     * 过滤 + 排序后的应用列表。
     * 依赖 apps / searchQuery / showSystemApps / sortAnchor，**不依赖 selectedPackages**——
     * 勾选只改变 checkbox 外观，不引起列表顺序变化（避免可见抖动）。
     */
    val filteredAppsFlow: StateFlow<ImmutableList<AppInfo>> = combine(
        _uiState.map { ListInput(it.apps, it.searchQuery, it.showSystemApps) }.distinctUntilChanged(),
        _sortAnchor,
    ) { (apps, query, showSystem), anchor ->
        apps.searchAndSort(query, showSystem, anchor)
    }
        // 第一个订阅者出现时才枚举 PackageManager。VM 是 Koin single，随冷启动构造，
        // 而多数会话根本不会打开分应用代理页——枚举 + 逐包 loadLabel 是几百毫秒 CPU
        // 加一份常驻全量列表，白付
        .onStart { ensureAppsLoaded() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), persistentListOf())

    init {
        loadSavedState()
    }

    private fun loadSavedState() {
        val modeStr = storage.getString(StorageKeys.APP_PROXY_MODE, "AllowAll")
        val mode = try {
            AppProxyMode.valueOf(modeStr)
        } catch (_: Exception) {
            AppProxyMode.AllowAll
        }

        val packages = storage.getStringSet(StorageKeys.APP_PROXY_PACKAGES, emptySet()).toPersistentSet()

        initialMode = mode
        initialPackages = packages
        _sortAnchor.value = packages

        _uiState.value = _uiState.value.copy(mode = mode, selectedPackages = packages)
    }

    /** 自动加载每进程只执行一次；手动刷新可重新枚举应用。 */
    private fun ensureAppsLoaded() {
        if (appsLoadJob != null) return
        loadApps()
    }

    private fun loadApps(refresh: Boolean = false) {
        appsLoadJob = viewModelScope.launch {
            if (refresh) _uiState.value = _uiState.value.copy(isRefreshing = true)
            try {
                val apps = appListProvider.getInstalledApps()
                if (refresh) _sortAnchor.value = _uiState.value.selectedPackages
                _uiState.value = _uiState.value.copy(apps = apps)
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false, isRefreshing = false)
            }
        }
    }

    /**
     * selectAll / invertSelection 等需要当前可见列表的 package 集合。
     * 直接读取 Flow 当前值，避免二次排序。
     */
    fun filteredApps(searchQuery: String = _uiState.value.searchQuery): List<AppInfo> {
        val state = _uiState.value
        if (searchQuery == state.searchQuery) return filteredAppsFlow.value
        // 查询词尚未同步到 uiState：做一次即时过滤，不排序（调用点仅用 packageName）
        return state.apps.filterApps(searchQuery, state.showSystemApps)
    }

    fun toggleApp(packageName: String) {
        val current = _uiState.value.selectedPackages
        applySelection(
            if (packageName in current) current.removing(packageName) else current.adding(packageName)
        )
    }

    fun selectAll() {
        applySelection(_uiState.value.selectedPackages.addingAll(visiblePackages()))
    }

    fun deselectAll() {
        applySelection(persistentSetOf())
    }

    fun invertSelection() {
        val current = _uiState.value.selectedPackages
        val visible = visiblePackages()
        // 保留不可见的已选项，对可见项取反
        applySelection(current.removingAll(visible).addingAll(visible - current))
    }

    private fun visiblePackages(): Set<String> = filteredApps().mapTo(mutableSetOf()) { it.packageName }

    private fun applySelection(packages: PersistentSet<String>) {
        _uiState.value = _uiState.value.copy(selectedPackages = packages)
        storage.putStringSet(StorageKeys.APP_PROXY_PACKAGES, packages)
    }

    fun setMode(mode: AppProxyMode) {
        _uiState.value = _uiState.value.copy(mode = mode)
        storage.putString(StorageKeys.APP_PROXY_MODE, mode.name)
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun setShowSystemApps(show: Boolean) {
        _uiState.value = _uiState.value.copy(showSystemApps = show)
    }

    fun refreshApps() {
        if (appsLoadJob?.isActive == true) return
        loadApps(refresh = true)
    }

    fun exportPackages(): String {
        return _uiState.value.selectedPackages.sorted().joinToString("\n")
    }

    fun importPackages(text: String) {
        applySelection(
            text.lines().map { it.trim() }.filter { it.isNotEmpty() }.toPersistentSet()
        )
    }

    /** 配置变更时，若代理运行中则自动重启服务。返回是否发生了变更。 */
    fun applyIfChanged(): Boolean {
        val state = _uiState.value
        val changed = state.mode != initialMode || state.selectedPackages != initialPackages
        if (!changed) return false

        val proxyState = ProxyServiceBridge.state.value.state
        if (proxyState == ProxyState.Running || proxyState == ProxyState.Starting) {
            serviceController?.restart()
        }

        // 更新快照
        initialMode = state.mode
        initialPackages = state.selectedPackages
        return true
    }

    private companion object {
        /** 离开页面到停止共享的宽限期，覆盖旋转屏 / 短暂切走导致的重订阅 */
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}

private fun List<AppInfo>.searchAndSort(
    query: String,
    showSystemApps: Boolean,
    anchor: Set<String>,
): ImmutableList<AppInfo> {
    val visible = if (showSystemApps) this else filter { !it.isSystemApp }
    val defaultOrder = compareByDescending<AppInfo> { it.packageName in anchor }
        .thenBy { it.appName.lowercase() }
    val parsedQuery = SearchQuery.parse(query)
    if (parsedQuery.isEmpty) {
        return visible.sortedWith(defaultOrder).toPersistentList()
    }
    return SearchMatcher.rank(
        items = visible,
        query = parsedQuery,
        tieBreaker = defaultOrder,
    ) { app ->
        field(app.appName, allowAcronym = true)
        field(app.packageName, allowAcronym = true)
    }.toPersistentList()
}

private fun List<AppInfo>.filterApps(query: String, showSystemApps: Boolean): List<AppInfo> {
    val visible = if (showSystemApps) this else filter { !it.isSystemApp }
    val parsedQuery = SearchQuery.parse(query)
    if (parsedQuery.isEmpty) return visible
    return SearchMatcher.filter(visible, parsedQuery) { app ->
        field(app.appName, allowAcronym = true)
        field(app.packageName, allowAcronym = true)
    }
}
