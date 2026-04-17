package top.yukonga.mishka.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import top.yukonga.mishka.data.database.AppDatabase
import top.yukonga.mishka.data.model.ProfileType
import top.yukonga.mishka.data.model.Subscription
import top.yukonga.mishka.data.repository.ConfigProcessor
import top.yukonga.mishka.data.repository.ConfigValidationException
import top.yukonga.mishka.data.repository.SubscriptionFetcher
import top.yukonga.mishka.data.repository.SubscriptionRepository
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProfileFileManager

@Immutable
data class ImportProgress(
    val step: String,
    val current: Int = 0,
    val total: Int = 0,
)

@Immutable
data class SubscriptionUiState(
    val subscriptions: List<Subscription> = emptyList(),
    val isLoading: Boolean = false,
    val error: String = "",
    val showAddDialog: Boolean = false,
    val importProgress: ImportProgress? = null,
)

class SubscriptionViewModel(
    database: AppDatabase,
    storage: PlatformStorage,
    val fileManager: ProfileFileManager,
) : ViewModel() {

    private val repository = SubscriptionRepository(
        importedDao = database.importedDao(),
        pendingDao = database.pendingDao(),
        selectionDao = database.selectionDao(),
        storage = storage,
        fileManager = fileManager,
        scope = viewModelScope,
    )
    private val fetcher = SubscriptionFetcher()

    private val _uiState = MutableStateFlow(SubscriptionUiState())
    val uiState: StateFlow<SubscriptionUiState> = _uiState.asStateFlow()

    init {
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
     * URL 导入：create Pending → 下载配置 → 校验 → commit → Imported
     */
    fun addSubscription(name: String, url: String, interval: Long = 0, onComplete: () -> Unit = {}) {
        hideAddDialog()
        _uiState.value = _uiState.value.copy(isLoading = true, error = "", importProgress = null)

        viewModelScope.launch {
            var subId: String? = null
            try {
                val sub = repository.create(ProfileType.Url, name, url, interval)
                subId = sub.id
                yield()

                _uiState.value = _uiState.value.copy(
                    importProgress = ImportProgress("下载配置...")
                )
                val result = fetcher.fetch(sub)

                processAndCommit(result.subscription, result.configContent)
                onComplete()
            } catch (e: ConfigValidationException) {
                subId?.let { cleanupFailedPending(it) }
                _uiState.value = _uiState.value.copy(
                    isLoading = false, importProgress = null,
                    error = e.message ?: "配置校验失败",
                )
            } catch (e: Exception) {
                subId?.let { cleanupFailedPending(it) }
                _uiState.value = _uiState.value.copy(
                    isLoading = false, importProgress = null,
                    error = "导入失败: ${e.message}",
                )
            }
        }
    }

    /**
     * 文件导入：create Pending → 校验 → commit → Imported
     */
    fun addFromFile(fileName: String, content: String, onComplete: () -> Unit = {}) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = "", importProgress = null)

        viewModelScope.launch {
            var subId: String? = null
            try {
                val name = fileName.removeSuffix(".yaml").removeSuffix(".yml")
                val sub = repository.create(ProfileType.File, name, "")
                subId = sub.id
                yield()

                val updated = sub.copy(updatedAt = System.currentTimeMillis())
                processAndCommit(updated, content)
                onComplete()
            } catch (e: ConfigValidationException) {
                subId?.let { cleanupFailedPending(it) }
                _uiState.value = _uiState.value.copy(
                    isLoading = false, importProgress = null,
                    error = e.message ?: "配置校验失败",
                )
            } catch (e: Exception) {
                subId?.let { cleanupFailedPending(it) }
                _uiState.value = _uiState.value.copy(
                    isLoading = false, importProgress = null,
                    error = "导入失败: ${e.message}",
                )
            }
        }
    }

    /**
     * 手动刷新已导入的订阅（直接更新 Imported，不经过 Pending）。
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
                processAndUpdateImported(result.subscription, result.configContent)
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
        viewModelScope.launch {
            repository.delete(id)
            fileManager.deleteDirs(id)
        }
    }

    fun setActive(id: String) {
        viewModelScope.launch {
            repository.setActive(id)
        }
    }

    fun updateAllSubscriptions() {
        _uiState.value.subscriptions
            .filter { it.url.isNotBlank() }
            .forEach { sub -> fetchSubscription(sub.id) }
    }

    fun getActiveSubscription(): Subscription? = repository.getActive()

    /**
     * 编辑订阅属性（名称、URL、更新间隔），通过 Pending → Commit 两阶段。
     */
    fun editSubscription(uuid: String, name: String, source: String, interval: Long, onComplete: () -> Unit = {}) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = "", importProgress = null)

        viewModelScope.launch {
            try {
                repository.patch(uuid, name, source, interval)
                repository.commit(uuid)
                onComplete()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "保存失败: ${e.message}",
                )
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false, importProgress = null)
            }
        }
    }

    /**
     * 复制已导入的订阅。
     */
    fun duplicateSubscription(uuid: String) {
        viewModelScope.launch {
            try {
                val newUuid = repository.clone(uuid)
                fileManager.cloneFiles(uuid, newUuid)
                repository.commit(newUuid)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "复制失败: ${e.message}",
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        fetcher.close()
    }

    private suspend fun processAndCommit(subscription: Subscription, configContent: String) = withContext(Dispatchers.IO) {
        fileManager.saveConfig(subscription.id, configContent)

        downloadProviders(subscription.id, configContent)

        _uiState.value = _uiState.value.copy(
            importProgress = ImportProgress("验证配置...")
        )
        yield()
        val workDir = fileManager.getDir(subscription.id)
        fileManager.ensureGeodataAvailable(workDir)
        val error = validateWithOverrides(subscription.id, workDir)
        if (error == null) {
            fileManager.collectGeodata(workDir)
        }
        if (error != null) {
            throw ConfigValidationException(error)
        }

        repository.commit(
            uuid = subscription.id,
            upload = subscription.upload,
            download = subscription.download,
            total = subscription.total,
            expire = subscription.expire,
        )
        repository.setActive(subscription.id)

        _uiState.value = _uiState.value.copy(isLoading = false, importProgress = null)
    }

    private suspend fun processAndUpdateImported(subscription: Subscription, configContent: String) = withContext(Dispatchers.IO) {
        fileManager.saveConfig(subscription.id, configContent)

        downloadProviders(subscription.id, configContent)

        _uiState.value = _uiState.value.copy(
            importProgress = ImportProgress("验证配置...")
        )
        yield()
        val workDir = fileManager.getDir(subscription.id)
        fileManager.ensureGeodataAvailable(workDir)
        val error = validateWithOverrides(subscription.id, workDir)
        if (error == null) {
            fileManager.collectGeodata(workDir)
        }
        if (error != null) {
            throw ConfigValidationException(error)
        }

        repository.updateImported(
            uuid = subscription.id,
            name = subscription.name,
            upload = subscription.upload,
            download = subscription.download,
            total = subscription.total,
            expire = subscription.expire,
        )

        _uiState.value = _uiState.value.copy(isLoading = false, importProgress = null)
    }

    private suspend fun downloadProviders(subscriptionId: String, configContent: String) {
        val providers = ConfigProcessor.parseProviders(configContent)
        val downloadable = providers.filter { it.url.isNotEmpty() }

        if (downloadable.isNotEmpty()) {
            val downloadResult = ConfigProcessor.downloadProviders(
                providers = providers,
                subscriptionDir = fileManager.getDir(subscriptionId),
                downloader = { url -> fetcher.downloadBytes(url) },
                onProgress = { current, total, _ ->
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
                    error = "部分外部资源下载失败（${downloadResult.failures.size} 个，启动时将重试）",
                )
            }
        }
    }

    private suspend fun cleanupFailedPending(uuid: String) {
        repository.release(uuid)
        fileManager.releasePending(uuid)
    }

    /**
     * 生成 override 合并后的临时配置并 mihomo -t 校验，try/finally 确保清理临时文件。
     * 校验将要运行的 YAML 而非原始订阅，捕捉 override（DNS/port/sniffer 等）与订阅的交互 bug。
     */
    private suspend fun validateWithOverrides(uuid: String, workDir: String): String? {
        val validationFileName = fileManager.generateValidationConfig(uuid)
        return try {
            fileManager.validate(workDir, validationFileName) { provider ->
                _uiState.value = _uiState.value.copy(importProgress = ImportProgress(provider))
            }
        } finally {
            fileManager.cleanupValidationConfig(uuid)
        }
    }
}
