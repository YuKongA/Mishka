package top.yukonga.mishka.viewmodel

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.yukonga.mishka.R
import top.yukonga.mishka.data.repository.ConfigValidationException
import top.yukonga.mishka.data.repository.ImportError
import top.yukonga.mishka.data.repository.ImportProgress
import top.yukonga.mishka.data.repository.ProfileProcessor
import top.yukonga.mishka.domain.model.ProfileType
import top.yukonga.mishka.domain.model.Subscription
import top.yukonga.mishka.domain.repository.SubscriptionRepository
import top.yukonga.mishka.platform.ProfileFileManager
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.util.describe

/**
 * 当前进行中的订阅 Pipeline 操作类型，驱动 Dialog 标题与错误文案。
 */
enum class ProfileOperation { Import, Update, Edit }

@Immutable
data class SubscriptionUiState(
    val subscriptions: ImmutableList<Subscription> = persistentListOf(),
    val isLoading: Boolean = false,
    val error: String = "",
    val showAddDialog: Boolean = false,
    val importProgress: ImportProgress? = null,
    val updateAll: UpdateAllProgress? = null,
    val operation: ProfileOperation? = null,
)

/**
 * 批量"全部更新"进度。`completed` = 已完成条目数（0-based 正在处理的是第 completed+1 条）。
 * `currentStep` 由 ProfileProcessor.onProgress 回调推进，null 表示当前订阅还未开始内部阶段；
 * 本地化文案在 UI 层按 [ImportProgress] 映射。
 */
@Immutable
data class UpdateAllProgress(
    val completed: Int,
    val total: Int,
    val currentName: String,
    val currentStep: ImportProgress? = null,
)

class SubscriptionViewModel(
    private val repository: SubscriptionRepository,
    val fileManager: ProfileFileManager,
    private val processor: ProfileProcessor,
    private val serviceController: ProxyServiceController,
    private val context: Context,
) : ViewModel() {

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
    fun addSubscription(
        name: String,
        url: String,
        interval: Long = 0,
        userAgent: String = "",
        ageSecretKey: String = "",
        onComplete: () -> Unit = {},
    ) {
        hideAddDialog()
        runPipeline(ProfileOperation.Import, errorKey = R.string.error_import_failed) {
            val sub = repository.create(ProfileType.Url, name, url, interval, userAgent, ageSecretKey)
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
        runPipeline(ProfileOperation.Import, errorKey = R.string.error_import_failed) {
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
                _uiState.value = _uiState.value.copy(error = context.getString(R.string.subscription_file_only_no_update))
            }
            return
        }
        runPipeline(ProfileOperation.Update, errorKey = R.string.error_update_failed) {
            processor.update(id, ::reportProgress)
            serviceController.restartAfterProfileUpdate(id)
        }
    }

    /**
     * 删除订阅。删的若是 active，[onActiveChanged] 要等 DB 与文件都落定后才回调——
     * `repository.delete` 会静默把 active 挪到剩余第一条，提前回调会让重启拿到刚删掉的 uuid。
     */
    fun removeSubscription(id: String, onActiveChanged: () -> Unit = {}) {
        viewModelScope.launch {
            val wasActive = _uiState.value.subscriptions.find { it.id == id }?.isActive == true
            repository.delete(id)
            fileManager.deleteDirs(id)
            if (wasActive) onActiveChanged()
        }
    }

    fun setActive(id: String) {
        repository.setActive(id)
    }

    /**
     * 串行批量更新所有 URL 订阅。ProfileProcessor.processLock 已保证串行，这里显式 await
     * 便于每次只推进一条目的进度，避免并发 launch 让 `updateAll.currentName` 乱跳。
     * 失败聚合到 `error`；任一条失败不影响后续继续。CancellationException 直接传播
     * 让整个循环 cancel，不进 failures。
     *
     * 生效重启排在整轮之后：逐条重启会断流，后续订阅的下载（开了「通过代理更新」时走
     * mixed-port）会跟着一起失败。
     */
    fun updateAllSubscriptions() {
        val targets = _uiState.value.subscriptions.filter { it.url.isNotBlank() }
        if (targets.isEmpty()) return
        if (isBusy()) return

        _uiState.value = _uiState.value.copy(operation = ProfileOperation.Update, error = "")
        currentJob = viewModelScope.launch {
            val failures = mutableListOf<String>()
            val updated = mutableListOf<String>()
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
                                updateAll = state.copy(currentStep = progress),
                            )
                        }
                        updated += sub.id
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        val label = if (e is ConfigValidationException) {
                            context.getString(R.string.error_validation_failed, e.describe())
                        } else {
                            context.getString(R.string.error_update_failed, localizedMessage(e))
                        }
                        failures += "${sub.name}: $label"
                    }
                }
                _uiState.value = _uiState.value.copy(
                    updateAll = null,
                    operation = null,
                    error = if (failures.isEmpty()) "" else failures.joinToString("\n"),
                )
                // 非 active 的 uuid 在 controller 内被直接忽略，故至多触发一次重启
                updated.forEach { serviceController.restartAfterProfileUpdate(it) }
            } catch (e: CancellationException) {
                clearProgress()
                throw e
            } finally {
                currentJob = null
            }
        }
    }

    /**
     * 编辑订阅属性（名称、URL、更新间隔）。URL 类型走 ProfileProcessor 重新校验，
     * File 类型仅 patch DB（无 source 可重新拉取）。
     */
    fun editSubscription(
        uuid: String,
        name: String,
        source: String,
        interval: Long,
        userAgent: String,
        ageSecretKey: String,
        onComplete: () -> Unit = {},
    ) {
        runPipeline(ProfileOperation.Edit, errorKey = R.string.error_save_failed) {
            repository.patch(uuid, name, source, interval, userAgent, ageSecretKey)
            // 校验字段并判定是否需重新拉取：Url 订阅走完整 fetch 管线，File 订阅直接提交
            if (repository.validatePendingForCommit(uuid)) {
                processor.apply(uuid, ::reportProgress)
            } else {
                repository.commitPendingProfile(uuid)
            }
            onComplete()
        }
    }

    // === 内部辅助 ===

    /**
     * Pipeline 通用外壳：防并发 + 标记 operation + 进度状态初始化 + 统一错误/取消处理。
     *
     * 成功：清 isLoading + importProgress + operation，保留 updateAll 由调用方管理（批量路径自管）。
     * CancellationException：clearProgress → 协程取消传播（外部 cancelCurrentUpdate 已预清）。
     * 其他异常：context.getString(errorKey, describe) → 写入 error；清 progress。
     */
    private fun runPipeline(
        op: ProfileOperation,
        @StringRes errorKey: Int,
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
                    context.getString(R.string.error_validation_failed, e.describe())
                } else {
                    context.getString(errorKey, localizedMessage(e))
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
     * 把 data 层的类型化错误映射成本地化文案。[ImportError] 的 message 只是英文技术描述，
     * 直接展示既不合语言也读不懂；其余异常仍走 [describe] 兜住无参异常的 null message。
     */
    private fun localizedMessage(e: Throwable): String = when (e) {
        is ImportError.EmptyBody -> context.getString(R.string.error_import_empty_body)
        is ImportError.InvalidScheme -> context.getString(R.string.error_import_invalid_scheme, e.source)
        is ImportError.InvalidName -> context.getString(R.string.error_import_invalid_name)
        is ImportError.IntervalTooSmall -> context.getString(R.string.error_import_interval_too_small)
        // HttpStatus 本身就是 "HTTP 404 Not Found" 这类技术串，无可本地化的部分
        else -> e.describe()
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
