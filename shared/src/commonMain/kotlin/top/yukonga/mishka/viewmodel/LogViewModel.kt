package top.yukonga.mishka.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.launch
import top.yukonga.mishka.data.model.LogMessage
import top.yukonga.mishka.data.repository.MihomoRepository

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
    private val _logs = MutableStateFlow<List<IndexedLog>>(emptyList())
    val logs: StateFlow<List<IndexedLog>> = _logs.asStateFlow()

    private var repository: MihomoRepository? = null
    private var logJob: Job? = null
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
    }

    private fun startLogCollection() {
        logJob?.cancel()
        val repo = repository ?: return

        logJob = viewModelScope.launch {
            repo.logsFlow(_uiState.value.level)
                .buffer(capacity = BUFFER_CAPACITY)
                .collect { log ->
                    appendLog(log)
                }
        }
    }

    private fun appendLog(log: LogMessage) {
        buffer.addLast(IndexedLog(nextLogId++, log))
        while (buffer.size > MAX_LOGS) {
            buffer.removeFirst()
        }
        _logs.value = buffer.toList()
    }

    fun setLevel(level: String) {
        _uiState.value = _uiState.value.copy(level = level)
        resetLogs()
        startLogCollection()
    }

    fun clearLogs() {
        resetLogs()
    }

    private fun resetLogs() {
        buffer.clear()
        nextLogId = 0L
        _logs.value = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        logJob?.cancel()
        connectionStateJob?.cancel()
    }

    companion object {
        private const val MAX_LOGS = 500
        private const val BUFFER_CAPACITY = 64
    }
}
