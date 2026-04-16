package top.yukonga.mishka.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.yukonga.mishka.data.model.LogMessage
import top.yukonga.mishka.data.repository.MihomoRepository

data class LogUiState(
    val isConnected: Boolean = false,
    val level: String = "info",
)

data class IndexedLog(val id: Long, val message: LogMessage)

class LogViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(LogUiState())
    val uiState: StateFlow<LogUiState> = _uiState.asStateFlow()

    private var nextLogId = 0L
    private val _logs = MutableStateFlow<List<IndexedLog>>(emptyList())
    val logs: StateFlow<List<IndexedLog>> = _logs.asStateFlow()

    private var repository: MihomoRepository? = null
    private var logJob: Job? = null

    fun setRepository(repo: MihomoRepository?) {
        if (repo == null) {
            disconnect()
            _logs.value = emptyList()
            nextLogId = 0L
        }
        repository = repo
    }

    fun connect() {
        if (logJob?.isActive == true) return
        startLogCollection()
    }

    fun disconnect() {
        logJob?.cancel()
        logJob = null
        _uiState.value = _uiState.value.copy(isConnected = false)
    }

    private fun startLogCollection() {
        logJob?.cancel()
        val repo = repository ?: return

        logJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isConnected = true)
            repo.logsFlow(_uiState.value.level)
                .catch {
                    _uiState.value = _uiState.value.copy(isConnected = false)
                }
                .collect { log ->
                    _logs.update { (it + IndexedLog(nextLogId++, log)).takeLast(500) }
                }
        }
    }

    fun setLevel(level: String) {
        _uiState.value = _uiState.value.copy(level = level)
        _logs.value = emptyList()
        nextLogId = 0L
        startLogCollection()
    }

    fun clearLogs() {
        _logs.value = emptyList()
        nextLogId = 0L
    }

    override fun onCleared() {
        super.onCleared()
        logJob?.cancel()
    }
}
