package top.yukonga.mishka.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import top.yukonga.mishka.data.model.Subscription
import top.yukonga.mishka.data.repository.ConfigProcessor
import top.yukonga.mishka.data.repository.ConfigValidationException
import top.yukonga.mishka.data.repository.SubscriptionFetcher
import top.yukonga.mishka.data.repository.SubscriptionRepository
import top.yukonga.mishka.platform.PlatformStorage

data class ImportProgress(
    val step: String,
    val current: Int = 0,
    val total: Int = 0,
)

data class SubscriptionUiState(
    val subscriptions: List<Subscription> = emptyList(),
    val isLoading: Boolean = false,
    val error: String = "",
    val showAddDialog: Boolean = false,
    val importProgress: ImportProgress? = null,
)

class SubscriptionViewModel(
    storage: PlatformStorage,
    private val onConfigSaved: (subscriptionId: String, content: String) -> Unit,
    private val getSubscriptionDir: (subscriptionId: String) -> String,
) : ViewModel() {

    private val repository = SubscriptionRepository(storage)
    private val fetcher = SubscriptionFetcher()

    private val _uiState = MutableStateFlow(SubscriptionUiState())
    val uiState: StateFlow<SubscriptionUiState> = _uiState.asStateFlow()

    init {
        repository.load()
        viewModelScope.launch {
            repository.subscriptions.collect { subs ->
                _uiState.value = _uiState.value.copy(subscriptions = subs)
            }
        }
    }

    fun showAddDialog() {
        _uiState.value = _uiState.value.copy(showAddDialog = true)
    }

    fun hideAddDialog() {
        _uiState.value = _uiState.value.copy(showAddDialog = false)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = "")
    }

    /**
     * URL 导入：创建订阅 → 下载配置 → 校验 → 保存 → 下载 provider
     */
    fun addSubscription(name: String, url: String, onComplete: () -> Unit = {}) {
        val sub = repository.add(name, url)
        repository.setActive(sub.id)
        hideAddDialog()

        _uiState.value = _uiState.value.copy(isLoading = true, error = "", importProgress = null)

        viewModelScope.launch {
            yield() // 让 Compose 重组显示 Dialog
            try {
                // 1. 下载配置
                _uiState.value = _uiState.value.copy(
                    importProgress = ImportProgress("下载配置...")
                )
                val result = fetcher.fetch(sub)

                // 2. 校验 + 保存 + 下载 provider
                processConfig(result.subscription, result.configContent)
                onComplete()
            } catch (e: ConfigValidationException) {
                repository.remove(sub.id)
                _uiState.value = _uiState.value.copy(
                    isLoading = false, importProgress = null,
                    error = e.message ?: "配置校验失败",
                )
            } catch (e: Exception) {
                repository.remove(sub.id)
                _uiState.value = _uiState.value.copy(
                    isLoading = false, importProgress = null,
                    error = "导入失败: ${e.message}",
                )
            }
        }
    }

    /**
     * 文件导入：校验 → 保存 → 下载 provider
     */
    fun addFromFile(fileName: String, content: String, onComplete: () -> Unit = {}) {
        val name = fileName.removeSuffix(".yaml").removeSuffix(".yml")
        val sub = repository.add(name, "")

        _uiState.value = _uiState.value.copy(isLoading = true, error = "", importProgress = null)

        viewModelScope.launch {
            yield() // 让 Compose 重组显示 Dialog
            try {
                val updated = sub.copy(updatedAt = System.currentTimeMillis())
                repository.setActive(sub.id)

                processConfig(updated, content)
                onComplete()
            } catch (e: ConfigValidationException) {
                repository.remove(sub.id)
                _uiState.value = _uiState.value.copy(
                    isLoading = false, importProgress = null,
                    error = e.message ?: "配置校验失败",
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false, importProgress = null,
                    error = "导入失败: ${e.message}",
                )
            }
        }
    }

    /**
     * 手动刷新已有订阅
     */
    fun fetchSubscription(id: String) {
        val sub = _uiState.value.subscriptions.find { it.id == id } ?: return
        if (sub.url.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "该配置为文件导入，无法在线更新")
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true, error = "", importProgress = null)

        viewModelScope.launch {
            yield()
            try {
                _uiState.value = _uiState.value.copy(
                    importProgress = ImportProgress("下载配置...")
                )
                val result = fetcher.fetch(sub)
                processConfig(result.subscription, result.configContent)
            } catch (e: ConfigValidationException) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false, importProgress = null,
                    error = e.message ?: "配置校验失败",
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false, importProgress = null,
                    error = "更新失败: ${e.message}",
                )
            }
        }
    }

    fun removeSubscription(id: String) {
        repository.remove(id)
    }

    fun setActive(id: String) {
        repository.setActive(id)
    }

    fun updateAllSubscriptions() {
        _uiState.value.subscriptions.forEach { sub ->
            fetchSubscription(sub.id)
        }
    }

    fun getActiveSubscription(): Subscription? = repository.getActive()

    override fun onCleared() {
        super.onCleared()
        fetcher.close()
    }

    /**
     * 共用的配置处理流程：校验 → 保存 → 解析 provider → 下载 provider
     */
    private suspend fun processConfig(subscription: Subscription, configContent: String) {
        // 1. 校验配置
        _uiState.value = _uiState.value.copy(
            importProgress = ImportProgress("验证配置...")
        )
        yield()
        ConfigProcessor.validate(configContent)

        // 2. 保存配置
        repository.update(subscription)
        onConfigSaved(subscription.id, configContent)

        // 3. 解析 provider
        val providers = ConfigProcessor.parseProviders(configContent)
        val downloadable = providers.filter { it.url.isNotEmpty() }

        if (downloadable.isNotEmpty()) {
            val downloadResult = ConfigProcessor.downloadProviders(
                providers = providers,
                subscriptionDir = getSubscriptionDir(subscription.id),
                downloader = { url -> fetcher.downloadBytes(url) },
                onProgress = { current, total, name ->
                    _uiState.value = _uiState.value.copy(
                        importProgress = ImportProgress(
                            step = "下载外部资源 ($current/$total)",
                            current = current,
                            total = total,
                        )
                    )
                },
            )

            if (downloadResult.failures.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false, importProgress = null,
                    error = "已导入，但 ${downloadResult.failures.size} 个外部资源下载失败（启动时将重试）",
                )
                return
            }
        }

        _uiState.value = _uiState.value.copy(isLoading = false, importProgress = null)
    }
}
