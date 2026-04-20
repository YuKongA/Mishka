package top.yukonga.mishka.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import top.yukonga.mishka.data.database.AppDatabase
import top.yukonga.mishka.data.model.ProfileType
import top.yukonga.mishka.data.model.Subscription
import top.yukonga.mishka.data.repository.ConfigValidationException
import top.yukonga.mishka.data.repository.ImportProgress
import top.yukonga.mishka.data.repository.OverrideJsonStore
import top.yukonga.mishka.data.repository.ProfileProcessor
import top.yukonga.mishka.data.repository.SubscriptionFetcher
import top.yukonga.mishka.data.repository.SubscriptionProxyResolver
import top.yukonga.mishka.data.repository.SubscriptionRepository
import top.yukonga.mishka.data.repository.enforceFieldValid
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProfileFileManager
import top.yukonga.mishka.util.describe

/**
 * 当前进行中的订阅 Pipeline 操作类型，驱动 Dialog 标题与错误文案。
 */
enum class ProfileOperation { Import, Update, Edit, Duplicate }

@Immutable
data class SubscriptionUiState(
    val subscriptions: List<Subscription> = emptyList(),
    val isLoading: Boolean = false,
    val error: String = "",
    val showAddDialog: Boolean = false,
    val importProgress: ImportProgress? = null,
    val updateAll: UpdateAllProgress? = null,
    val operation: ProfileOperation? = null,
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
    private val overrideStore = OverrideJsonStore(fileManager)
    private val proxyResolver = SubscriptionProxyResolver(storage, overrideStore)
    private val fetcher = SubscriptionFetcher(
        userAgent = userAgent,
        proxyUrlProvider = { proxyResolver.resolve() },
    )
    private val processor = ProfileProcessor(repository, fileManager, fetcher, proxyResolver)

    private val _uiState = MutableStateFlow(SubscriptionUiState())
    val uiState: StateFlow<SubscriptionUiState> = _uiState.asStateFlow()

    /**
     * 当前进行中的 Pipeline 任务（导入/更新/编辑/复制/单条刷新/批量更新共用此槽位）。
     * 允许用户通过 Dialog 的取消按钮终止；同时防止并发触发多个 Pipeline。
     */
    private var currentJob: Job? = null

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
     * 取消当前正在进行的订阅 Pipeline（导入 / 更新 / 编辑 / 复制）。
     *
     * **同步**清 UI 进度状态让 Dialog 立即 `show = false` —— 协程取消是异步的，
     * 如果阻塞在 `withContext(NonCancellable)` 的 commit 阶段或 Java IO 阻塞点，
     * 等 finally 触发 clearProgress 会让用户感觉取消"无反应"。
     * 然后 cancel 协程让后台清理跟进（processLock 释放、cleanupProcessing）。
     */
    fun cancelCurrentUpdate() {
        clearProgress()
        currentJob?.cancel()
    }

    /**
     * URL 导入：create Pending → ProfileProcessor.apply（fetch + validate + commit）
     */
    fun addSubscription(name: String, url: String, interval: Long = 0, onComplete: () -> Unit = {}) {
        hideAddDialog()
        runPipeline(ProfileOperation.Import, errorKey = Res.string.error_import_failed) {
            val sub = repository.create(ProfileType.Url, name, url, interval)
            pendingOnFailure(sub.id) {
                processor.apply(sub.id, ::reportProgress)
            }
            onComplete()
        }
    }

    /**
     * 文件导入：create Pending → savePendingConfig → ProfileProcessor.apply（File 类型跳过 fetch）
     */
    fun addFromFile(fileName: String, content: String, onComplete: () -> Unit = {}) {
        runPipeline(ProfileOperation.Import, errorKey = Res.string.error_import_failed) {
            val name = fileName.removeSuffix(".yaml").removeSuffix(".yml")
            val sub = repository.create(ProfileType.File, name, "")
            pendingOnFailure(sub.id) {
                fileManager.savePendingConfig(sub.id, content)
                processor.apply(sub.id, ::reportProgress)
            }
            onComplete()
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
        runPipeline(ProfileOperation.Update, errorKey = Res.string.error_update_failed) {
            processor.update(id, ::reportProgress)
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
     * 失败聚合到 `error`；任一条失败不影响后续继续。CancellationException 直接传播
     * 让整个循环 cancel，不进 failures。
     */
    fun updateAllSubscriptions() {
        val targets = _uiState.value.subscriptions.filter { it.url.isNotBlank() }
        if (targets.isEmpty()) return
        if (isBusy()) return

        _uiState.value = _uiState.value.copy(operation = ProfileOperation.Update, error = "")
        currentJob = viewModelScope.launch {
            val failures = mutableListOf<String>()
            val total = targets.size
            try {
                targets.forEachIndexed { index, sub ->
                    _uiState.value = _uiState.value.copy(
                        updateAll = UpdateAllProgress(index, total, sub.name),
                    )
                    try {
                        processor.update(sub.id) { progress ->
                            val state = _uiState.value.updateAll ?: return@update
                            _uiState.value = _uiState.value.copy(
                                updateAll = state.copy(currentStep = progress.step),
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
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
                    operation = null,
                    error = if (failures.isEmpty()) "" else failures.joinToString("\n"),
                )
            } catch (e: CancellationException) {
                clearProgress()
                throw e
            } finally {
                currentJob = null
            }
        }
    }

    fun getActiveSubscription(): Subscription? = repository.getActive()

    /**
     * 编辑订阅属性（名称、URL、更新间隔）。URL 类型走 ProfileProcessor 重新校验，
     * File 类型仅 patch DB（无 source 可重新拉取）。
     */
    fun editSubscription(uuid: String, name: String, source: String, interval: Long, onComplete: () -> Unit = {}) {
        runPipeline(ProfileOperation.Edit, errorKey = Res.string.error_save_failed) {
            repository.patch(uuid, name, source, interval)
            val pending = repository.queryPending(uuid)
            pending?.enforceFieldValid()
            if (pending?.type == ProfileType.Url && pending.source.isNotBlank()) {
                processor.apply(uuid, ::reportProgress)
            } else {
                repository.withProfileLock { repository.commitPending(uuid) }
            }
            onComplete()
        }
    }

    /**
     * 复制已导入订阅：create Pending(File) → cloneFiles imported→pending → 写 pending → apply。
     */
    fun duplicateSubscription(uuid: String) {
        runPipeline(ProfileOperation.Duplicate, errorKey = Res.string.error_duplicate_failed) {
            val newUuid = repository.clone(uuid)
            pendingOnFailure(newUuid) {
                fileManager.cloneFiles(uuid, newUuid)
                processor.apply(newUuid, ::reportProgress)
            }
        }
    }

    // === 内部辅助 ===

    /**
     * Pipeline 通用外壳：防并发 + 标记 operation + 进度状态初始化 + 统一错误/取消处理。
     *
     * 成功：清 isLoading + importProgress + operation，保留 updateAll 由调用方管理（批量路径自管）。
     * CancellationException：clearProgress → 协程取消传播（外部 cancelCurrentUpdate 已预清）。
     * 其他异常：getString(errorKey, describe) → 写入 error；清 progress。
     */
    private fun runPipeline(
        op: ProfileOperation,
        errorKey: StringResource,
        block: suspend () -> Unit,
    ) {
        if (isBusy()) return
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            error = "",
            importProgress = null,
            operation = op,
        )
        currentJob = viewModelScope.launch {
            try {
                block()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    importProgress = null,
                    operation = null,
                )
            } catch (e: CancellationException) {
                clearProgress()
                throw e
            } catch (e: Throwable) {
                val message = if (e is ConfigValidationException) {
                    getString(Res.string.error_validation_failed, e.describe())
                } else {
                    getString(errorKey, e.describe())
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    importProgress = null,
                    operation = null,
                    error = message,
                )
            } finally {
                currentJob = null
            }
        }
    }

    /**
     * 包装一个 Pending 创建后的动作：动作任意异常 / 取消时 release 该 Pending。
     * Pending 已经创建但后续步骤失败需要回滚，否则 DB 里会残留僵尸 Pending。
     * release 用 NonCancellable 保护，即使上层协程被 cancel 也能完成 DB/文件清理。
     */
    private suspend fun pendingOnFailure(uuid: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runCatching {
                    repository.release(uuid)
                    fileManager.releasePending(uuid)
                }
            }
            throw e
        }
    }

    private fun isBusy(): Boolean = currentJob?.isActive == true

    private fun clearProgress() {
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            importProgress = null,
            updateAll = null,
            operation = null,
        )
    }

    private fun reportProgress(p: ImportProgress) {
        _uiState.value = _uiState.value.copy(importProgress = p)
    }
}
