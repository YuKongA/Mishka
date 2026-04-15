package top.yukonga.mishka.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.yukonga.mishka.platform.AppInfo
import top.yukonga.mishka.platform.AppListProvider
import top.yukonga.mishka.platform.PlatformStorage

enum class AppProxyMode { AllowAll, AllowSelected, DenySelected }

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
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppProxyUiState())
    val uiState: StateFlow<AppProxyUiState> = _uiState.asStateFlow()

    init {
        loadSavedState()
        loadApps()
    }

    private fun loadSavedState() {
        val modeStr = storage.getString("app_proxy_mode", "AllowAll")
        val mode = try { AppProxyMode.valueOf(modeStr) } catch (_: Exception) { AppProxyMode.AllowAll }

        val packages = storage.getStringSet("app_proxy_packages", emptySet())

        _uiState.value = _uiState.value.copy(mode = mode, selectedPackages = packages)
    }

    private fun loadApps() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val apps = appListProvider.getInstalledApps()
            _uiState.value = _uiState.value.copy(apps = apps, isLoading = false)
        }
    }

    fun filteredApps(): List<AppInfo> {
        val state = _uiState.value
        val query = state.searchQuery.lowercase()

        return state.apps
            .filter { app ->
                // 系统应用过滤
                (state.showSystemApps || !app.isSystemApp) &&
                    // 搜索过滤
                    (query.isBlank() ||
                        app.appName.lowercase().contains(query) ||
                        app.packageName.lowercase().contains(query))
            }
            .sortedWith(
                // 已选应用排在前面
                compareByDescending<AppInfo> { it.packageName in state.selectedPackages }
                    .thenBy { it.appName.lowercase() }
            )
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
        val all = _uiState.value.apps.map { it.packageName }.toSet()
        val inverted = all - current
        _uiState.value = _uiState.value.copy(selectedPackages = inverted)
        savePackages(inverted)
    }

    fun setMode(mode: AppProxyMode) {
        _uiState.value = _uiState.value.copy(mode = mode)
        storage.putString("app_proxy_mode", mode.name)
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
        storage.putStringSet("app_proxy_packages", packages)
    }
}
