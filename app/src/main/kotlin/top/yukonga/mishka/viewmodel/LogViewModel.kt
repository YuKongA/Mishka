package top.yukonga.mishka.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.mishka.domain.model.LogMessage
import top.yukonga.mishka.domain.repository.MihomoRepository
import top.yukonga.mishka.util.ParsedLog
import top.yukonga.mishka.util.SearchMatcher
import top.yukonga.mishka.util.SearchQuery
import top.yukonga.mishka.util.logLevelTag
import top.yukonga.mishka.util.parseLogPayload

@Immutable
data class LogUiState(
    val isConnected: Boolean = false,
    val level: String = DEFAULT_LOG_LEVEL,
    val isConfigLoaded: Boolean = false,
    val isLogDisabled: Boolean = false,
) {
    companion object {
        const val DEFAULT_LOG_LEVEL = "info"
        const val LOG_LEVEL_SILENT = "silent"
    }
}

@Immutable
data class IndexedLog(
    val id: Long,
    val message: LogMessage,
    val parsed: ParsedLog = parseLogPayload(message.payload),
)

class LogViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(LogUiState())
    val uiState: StateFlow<LogUiState> = _uiState.asStateFlow()

    private var nextLogId = 0L
    private val buffer = ArrayDeque<IndexedLog>(MAX_LOGS)

    // 日志按行高频到达，缓冲在 buffer，由 flushJob 以显示帧率批量推到 _logs，
    // 避免每行 emit 让 LogScreen 以日志行速率重组。buffer/logsDirty 仅在 Main 访问，无需加锁
    private var logsDirty = false
    private val _logs = MutableStateFlow<ImmutableList<IndexedLog>>(persistentListOf())
    val logs: StateFlow<ImmutableList<IndexedLog>> = _logs.asStateFlow()

    private var repository: MihomoRepository? = null
    private var observing = false
    private var logJob: Job? = null
    private var flushJob: Job? = null
    private var connectionStateJob: Job? = null

    fun setRepository(repo: MihomoRepository?) {
        if (repo === repository) return
        // repository 切换（含 null）即视为新会话，清空旧 logs 避免跨会话串线
        repository = repo
        connectionStateJob?.cancel()
        connectionStateJob = null
        stopLogCollection()
        resetLogs()
        if (repo == null) {
            _uiState.value = LogUiState()
            return
        }
        _uiState.value = LogUiState(isConnected = repo.connectionState.value)
        connectionStateJob = viewModelScope.launch {
            repo.connectionState.collect { connected ->
                if (repository !== repo) return@collect
                _uiState.value = _uiState.value.copy(isConnected = connected)
            }
        }
        if (observing) {
            startLogCollection()
        }
    }

    fun connect() {
        observing = true
        if (logJob?.isActive == true) return
        if (_uiState.value.isConfigLoaded && _uiState.value.isLogDisabled) return
        startLogCollection()
    }

    fun disconnect() {
        observing = false
        stopLogCollection()
    }

    private fun stopLogCollection() {
        logJob?.cancel()
        logJob = null
        flushJob?.cancel()
        flushJob = null
    }

    private fun startLogCollection() {
        stopLogCollection()
        val repo = repository ?: return

        logJob = viewModelScope.launch {
            // 先读运行期生效的 log-level（已合并订阅配置、订阅覆写与全局覆写）：
            // mihomo 的 /logs WS 按 query 参数过滤而不检查全局 log.Level()，
            // 必须由客户端按 silent 判定是否禁用日志，避免无日志时一直显示「等待日志...」。
            val effectiveLevel = fetchEffectiveLogLevel(repo) ?: return@launch
            if (repository !== repo) return@launch
            val disabled = effectiveLevel == LogUiState.LOG_LEVEL_SILENT
            _uiState.value = _uiState.value.copy(
                level = effectiveLevel,
                isConfigLoaded = true,
                isLogDisabled = disabled,
            )
            if (disabled) return@launch

            startFlushLoop()
            repo.logsFlow(effectiveLevel)
                .buffer(capacity = BUFFER_CAPACITY)
                .collect { log ->
                    if (repository !== repo) return@collect
                    appendLog(log)
                }
        }
    }

    private suspend fun fetchEffectiveLogLevel(repo: MihomoRepository): String? {
        var retryDelayMs = CONFIG_RETRY_INITIAL_MS
        while (currentCoroutineContext().isActive) {
            val result = repo.getConfig()
            if (repository !== repo) return null
            result.onSuccess { config ->
                val normalized = config.logLevel.trim().lowercase()
                return normalized.takeIf { it in VALID_LOG_LEVELS } ?: LogUiState.DEFAULT_LOG_LEVEL
            }
            delay(retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(CONFIG_RETRY_MAX_MS)
        }
        return null
    }

    private fun startFlushLoop() {
        flushJob?.cancel()
        // 周期性把 buffer 刷到 UI，把重组频率从日志行速率降到显示帧率
        flushJob = viewModelScope.launch {
            while (isActive) {
                if (logsDirty) {
                    logsDirty = false
                    _logs.value = buffer.toPersistentList()
                }
                delay(FLUSH_INTERVAL_MS)
            }
        }
    }

    private fun appendLog(log: LogMessage) {
        buffer.addLast(IndexedLog(nextLogId++, log, parseLogPayload(log.payload)))
        while (buffer.size > MAX_LOGS) {
            buffer.removeFirst()
        }
        logsDirty = true
    }

    fun clearLogs() {
        resetLogs()
    }

    fun filteredLogs(searchQuery: String): ImmutableList<IndexedLog> {
        val parsedQuery = SearchQuery.parse(searchQuery)
        if (parsedQuery.isEmpty) return _logs.value
        return SearchMatcher.rank(
            items = _logs.value,
            query = parsedQuery,
            // 同相关度下新日志优先展示在搜索结果顶部
            tieBreaker = compareByDescending { it.id },
        ) { log ->
            val parsed = log.parsed
            val levelTag = logLevelTag(log.message.type)
            if (parsed.target.isNotEmpty()) {
                // 已拆解五元组的连接日志只按语义字段匹配，避开 raw 中无意义的 "match"/"using" 胶水词
                field(parsed.target, allowAcronym = true)
                field(parsed.protocol)
                field(parsed.proxy)
                field(parsed.rule)
                field(log.message.type, levelTag)
                field(parsed.source)
            } else {
                field(parsed.body.ifEmpty { log.message.payload })
                field(parsed.protocol)
                field(log.message.type, levelTag)
            }
        }.toPersistentList()
    }

    private fun resetLogs() {
        buffer.clear()
        nextLogId = 0L
        logsDirty = false
        _logs.value = persistentListOf()
    }

    companion object {
        private const val MAX_LOGS = 500
        private const val BUFFER_CAPACITY = 64
        private const val FLUSH_INTERVAL_MS = 120L
        private const val CONFIG_RETRY_INITIAL_MS = 500L
        private const val CONFIG_RETRY_MAX_MS = 5_000L
        private val VALID_LOG_LEVELS = setOf("debug", "info", "warning", "error", "silent")
    }
}
