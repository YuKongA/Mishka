package top.yukonga.mishka.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.mishka.domain.model.LogMessage
import top.yukonga.mishka.domain.repository.MihomoRepository

@Immutable
data class LogUiState(
    val isConnected: Boolean = false,
    val level: String = "info",
)

@Immutable
data class IndexedLog(val id: Long, val message: LogMessage)

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
    private var logJob: Job? = null
    private var flushJob: Job? = null
    private var connectionStateJob: Job? = null

    fun setRepository(repo: MihomoRepository?) {
        // repository 切换（含 null）即视为新会话，清空旧 logs 避免跨会话串线
        if (repo !== repository) {
            disconnect()
            resetLogs()
        }
        repository = repo
        connectionStateJob?.cancel()
        connectionStateJob = repo?.let {
            viewModelScope.launch {
                it.connectionState.collect { connected ->
                    _uiState.value = _uiState.value.copy(isConnected = connected)
                }
            }
        }
    }

    fun connect() {
        if (logJob?.isActive == true) return
        startLogCollection()
    }

    fun disconnect() {
        logJob?.cancel()
        logJob = null
        flushJob?.cancel()
        flushJob = null
    }

    private fun startLogCollection() {
        logJob?.cancel()
        flushJob?.cancel()
        val repo = repository ?: return

        logJob = viewModelScope.launch {
            repo.logsFlow(_uiState.value.level)
                .buffer(capacity = BUFFER_CAPACITY)
                .collect { log ->
                    appendLog(log)
                }
        }
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
        buffer.addLast(IndexedLog(nextLogId++, log))
        while (buffer.size > MAX_LOGS) {
            buffer.removeFirst()
        }
        logsDirty = true
    }

    fun clearLogs() {
        resetLogs()
    }

    fun filteredLogs(searchQuery: String): ImmutableList<IndexedLog> {
        val query = searchQuery.trim().lowercase()
        if (query.isEmpty()) return _logs.value
        return _logs.value.filter { log ->
            log.message.payload.lowercase().contains(query) ||
                    log.message.type.lowercase().contains(query)
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
    }
}
