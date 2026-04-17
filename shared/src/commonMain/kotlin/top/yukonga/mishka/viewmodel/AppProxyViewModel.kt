package top.yukonga.mishka.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.yukonga.mishka.platform.AppInfo
import top.yukonga.mishka.platform.AppListProvider
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.platform.ProxyState
import top.yukonga.mishka.platform.StorageKeys

enum class AppProxyMode { AllowAll, AllowSelected, DenySelected }

@Immutable
data class AppProxyUiState(
    val apps: List<AppInfo> = emptyList(),
    val selectedPackages: Set<String> = emptySet(),
    val mode: AppProxyMode = AppProxyMode.AllowAll,
    val searchQuery: String = "",
    val showSystemApps: Boolean = false,
    val isLoading: Boolean = true,
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
    private var initialPackages: Set<String> = emptySet()

    /** 排序锚点：进入页面时的初始勾选集合。整个会话保持不变，勾选不触发重排 */
    private val _sortAnchor = MutableStateFlow<Set<String>>(emptySet())

    /**
     * 过滤 + 排序后的应用列表。
     * 依赖 apps / searchQuery / showSystemApps / sortAnchor，**不依赖 selectedPackages**——
     * 勾选只改变 checkbox 外观，不引起列表顺序变化（避免可见抖动）。
     */
    val filteredAppsFlow: StateFlow<List<AppInfo>> = combine(
        _uiState.map { Triple(it.apps, it.searchQuery, it.showSystemApps) }.distinctUntilChanged(),
        _sortAnchor,
    ) { (apps, query, showSystem), anchor ->
        val q = query.lowercase()
        apps
            .filter { app ->
                (showSystem || !app.isSystemApp) &&
                    (q.isBlank() ||
                        app.appName.lowercase().contains(q) ||
                        app.packageName.lowercase().contains(q))
            }
            .sortedWith(
                compareByDescending<AppInfo> { it.packageName in anchor }
                    .thenBy { it.appName.lowercase() }
            )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        loadSavedState()
        loadApps()
    }

    private fun loadSavedState() {
        val modeStr = storage.getString(StorageKeys.APP_PROXY_MODE, "AllowAll")
        val mode = try { AppProxyMode.valueOf(modeStr) } catch (_: Exception) { AppProxyMode.AllowAll }

        val packages = storage.getStringSet(StorageKeys.APP_PROXY_PACKAGES, emptySet())

        initialMode = mode
        initialPackages = packages
        _sortAnchor.value = packages

        _uiState.value = _uiState.value.copy(mode = mode, selectedPackages = packages)
    }

    private fun loadApps() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val apps = appListProvider.getInstalledApps()
            _uiState.value = _uiState.value.copy(apps = apps, isLoading = false)
        }
    }

    /**
     * 对外兼容：selectAll / invertSelection 等需要当前可见列表的 package 集合。
     * 直接读取 Flow 当前值，避免二次排序。
     */
    fun filteredApps(searchQuery: String = _uiState.value.searchQuery): List<AppInfo> {
        if (searchQuery == _uiState.value.searchQuery) {
            return filteredAppsFlow.value
        }
        // 查询词尚未同步到 uiState：做一次即时过滤，不排序（调用点仅用 packageName）
        val state = _uiState.value
        val query = searchQuery.lowercase()
        return state.apps.filter { app ->
            (state.showSystemApps || !app.isSystemApp) &&
                (query.isBlank() ||
                    app.appName.lowercase().contains(query) ||
                    app.packageName.lowercase().contains(query))
        }
    }

    fun toggleApp(packageName: String) {
        val current = _uiState.value.selectedPackages
        val updated = if (packageName in current) current - packageName else current + packageName
        _uiState.value = _uiState.value.copy(selectedPackages = updated)
        savePackages(updated)
    }

    fun selectAll() {
        val all = filteredApps().map { it.packageName }.toSet()
        val updated = _uiState.value.selectedPackages + all
        _uiState.value = _uiState.value.copy(selectedPackages = updated)
        savePackages(updated)
    }

    fun deselectAll() {
        _uiState.value = _uiState.value.copy(selectedPackages = emptySet())
        savePackages(emptySet())
    }

    fun invertSelection() {
        val current = _uiState.value.selectedPackages
        val visible = filteredApps().map { it.packageName }.toSet()
        // 保留不可见的已选项，对可见项取反
        val inverted = (current - visible) + (visible - current)
        _uiState.value = _uiState.value.copy(selectedPackages = inverted)
        savePackages(inverted)
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

    fun exportPackages(): String {
        return _uiState.value.selectedPackages.sorted().joinToString("\n")
    }

    fun importPackages(text: String) {
        val packages = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        _uiState.value = _uiState.value.copy(selectedPackages = packages)
        savePackages(packages)
    }

    private fun savePackages(packages: Set<String>) {
        storage.putStringSet(StorageKeys.APP_PROXY_PACKAGES, packages)
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
}
