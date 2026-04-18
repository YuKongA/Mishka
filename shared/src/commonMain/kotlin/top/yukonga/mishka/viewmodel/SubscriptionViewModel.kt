package top.yukonga.mishka.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.error_duplicate_failed
import mishka.shared.generated.resources.error_import_failed
import mishka.shared.generated.resources.error_save_failed
import mishka.shared.generated.resources.error_update_failed
import mishka.shared.generated.resources.error_validation_failed
import mishka.shared.generated.resources.subscription_file_only_no_update
import org.jetbrains.compose.resources.getString
import top.yukonga.mishka.data.database.AppDatabase
import top.yukonga.mishka.data.model.ProfileType
import top.yukonga.mishka.data.model.Subscription
import top.yukonga.mishka.data.repository.ConfigValidationException
import top.yukonga.mishka.data.repository.ImportProgress
import top.yukonga.mishka.data.repository.ProfileProcessor
import top.yukonga.mishka.data.repository.SubscriptionFetcher
import top.yukonga.mishka.data.repository.SubscriptionRepository
import top.yukonga.mishka.data.repository.enforceFieldValid
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProfileFileManager
import top.yukonga.mishka.util.describe

@Immutable
data class SubscriptionUiState(
    val subscriptions: List<Subscription> = emptyList(),
    val isLoading: Boolean = false,
    val error: String = "",
    val showAddDialog: Boolean = false,
    val importProgress: ImportProgress? = null,
    val updateAll: UpdateAllProgress? = null,
)

/**
 * 批量"全部更新"进度。`completed` = 已完成条目数（0-based 正在处理的是第 completed+1 条）。
 * `currentStep` 由 ProfileProcessor.onProgress 回调推进，允许空串表示当前订阅还未开始内部阶段。
 */
@Immutable
data class UpdateAllProgress(
    val completed: Int,
    val total: Int,
    val currentName: String,
    val currentStep: String = "",
)

class SubscriptionViewModel(
    database: AppDatabase,
    storage: PlatformStorage,
    val fileManager: ProfileFileManager,
    userAgent: String,
) : ViewModel() {

    private val repository = SubscriptionRepository(
        importedDao = database.importedDao(),
        pendingDao = database.pendingDao(),
        selectionDao = database.selectionDao(),
        storage = storage,
        fileManager = fileManager,
        scope = viewModelScope,
    )
    private val fetcher = SubscriptionFetcher(userAgent = userAgent)
    private val processor = ProfileProcessor(repository, fileManager, fetcher)

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
     * URL 导入：create Pending → ProfileProcessor.apply（fetch + validate + commit）
     */
    fun addSubscription(name: String, url: String, interval: Long = 0, onComplete: () -> Unit = {}) {
        hideAddDialog()
        beginImport()
        viewModelScope.launch {
            var subId: String? = null
            try {
                val sub = repository.create(ProfileType.Url, name, url, interval)
                subId = sub.id
                processor.apply(sub.id, ::reportProgress)
                finishImport()
                onComplete()
            } catch (e: Throwable) {
                subId?.let { releaseFailedPending(it) }
                showError(Res.string.error_import_failed, e)
            }
        }
    }

    /**
     * 文件导入：create Pending → savePendingConfig → ProfileProcessor.apply（File 类型跳过 fetch）
     */
    fun addFromFile(fileName: String, content: String, onComplete: () -> Unit = {}) {
        beginImport()
        viewModelScope.launch {
            var subId: String? = null
            try {
                val name = fileName.removeSuffix(".yaml").removeSuffix(".yml")
                val sub = repository.create(ProfileType.File, name, "")
                subId = sub.id
                fileManager.savePendingConfig(sub.id, content)
                processor.apply(sub.id, ::reportProgress)
                finishImport()
                onComplete()
            } catch (e: Throwable) {
                subId?.let { releaseFailedPending(it) }
                showError(Res.string.error_import_failed, e)
            }
        }
    }

    /**
     * 手动刷新已导入的订阅。
     */
    fun fetchSubscription(id: String) {
        val sub = _uiState.value.subscriptions.find { it.id == id } ?: return
        if (sub.url.isBlank()) {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(error = getString(Res.string.subscription_file_only_no_update))
            }
            return
        }
        beginImport()
        viewModelScope.launch {
            try {
                processor.update(id, ::reportProgress)
                finishImport()
            } catch (e: Throwable) {
                showError(Res.string.error_update_failed, e)
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

    /**
     * 串行批量更新所有 URL 订阅。ProfileProcessor.processLock 已保证串行，这里显式 await
     * 便于每次只推进一条目的进度，避免并发 launch 让 `updateAll.currentName` 乱跳。
     * 失败聚合到 `error`；任一条失败不影响后续继续。
     */
    fun updateAllSubscriptions() {
        val targets = _uiState.value.subscriptions.filter { it.url.isNotBlank() }
        if (targets.isEmpty()) return
        if (_uiState.value.updateAll != null || _uiState.value.isLoading) return

        viewModelScope.launch {
            val failures = mutableListOf<String>()
            val total = targets.size
            targets.forEachIndexed { index, sub ->
                _uiState.value = _uiState.value.copy(
                    updateAll = UpdateAllProgress(index, total, sub.name),
                    error = "",
                )
                try {
                    processor.update(sub.id) { progress ->
                        val state = _uiState.value.updateAll ?: return@update
                        _uiState.value = _uiState.value.copy(
                            updateAll = state.copy(currentStep = progress.step),
                        )
                    }
                } catch (e: Throwable) {
                    val label = if (e is ConfigValidationException) {
                        getString(Res.string.error_validation_failed, e.describe())
                    } else {
                        getString(Res.string.error_update_failed, e.describe())
                    }
                    failures += "${sub.name}: $label"
                }
            }
            _uiState.value = _uiState.value.copy(
                updateAll = null,
                error = if (failures.isEmpty()) "" else failures.joinToString("\n"),
            )
        }
    }

    fun getActiveSubscription(): Subscription? = repository.getActive()

    /**
     * 编辑订阅属性（名称、URL、更新间隔）。URL 类型走 ProfileProcessor 重新校验，
     * File 类型仅 patch DB（无 source 可重新拉取）。
     */
    fun editSubscription(uuid: String, name: String, source: String, interval: Long, onComplete: () -> Unit = {}) {
        beginImport()
        viewModelScope.launch {
            try {
                repository.patch(uuid, name, source, interval)
                val pending = repository.queryPending(uuid)
                pending?.enforceFieldValid()
                if (pending?.type == ProfileType.Url && pending.source.isNotBlank()) {
                    processor.apply(uuid, ::reportProgress)
                } else {
                    repository.withProfileLock { repository.commitPending(uuid) }
                }
                finishImport()
                onComplete()
            } catch (e: Throwable) {
                showError(Res.string.error_save_failed, e)
            }
        }
    }

    /**
     * 复制已导入订阅：create Pending(File) → cloneFiles imported→pending → 写 pending → apply。
     */
    fun duplicateSubscription(uuid: String) {
        beginImport()
        viewModelScope.launch {
            try {
                val newUuid = repository.clone(uuid)
                fileManager.cloneFiles(uuid, newUuid)
                processor.apply(newUuid, ::reportProgress)
                finishImport()
            } catch (e: Throwable) {
                showError(Res.string.error_duplicate_failed, e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        fetcher.close()
    }

    // === 内部辅助 ===

    private fun beginImport() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = "", importProgress = null)
    }

    private fun finishImport() {
        _uiState.value = _uiState.value.copy(isLoading = false, importProgress = null)
    }

    private fun reportProgress(p: ImportProgress) {
        _uiState.value = _uiState.value.copy(importProgress = p)
    }

    private fun showError(prefixKey: org.jetbrains.compose.resources.StringResource, e: Throwable) {
        viewModelScope.launch {
            val message = if (e is ConfigValidationException) {
                getString(Res.string.error_validation_failed, e.describe())
            } else {
                getString(prefixKey, e.describe())
            }
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                importProgress = null,
                error = message,
            )
        }
    }

    private suspend fun releaseFailedPending(uuid: String) {
        repository.release(uuid)
        fileManager.releasePending(uuid)
    }
}
