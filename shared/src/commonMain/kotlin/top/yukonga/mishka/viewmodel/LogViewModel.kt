package top.yukonga.mishka.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import top.yukonga.mishka.data.model.LogMessage
import top.yukonga.mishka.data.repository.MihomoRepository

data class LogUiState(
    val logs: List<LogMessage> = emptyList(),
    val isConnected: Boolean = false,
    val level: String = "info",
)

class LogViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(LogUiState())
    val uiState: StateFlow<LogUiState> = _uiState.asStateFlow()

    private var repository: MihomoRepository? = null
    private var logJob: Job? = null

    fun setRepository(repo: MihomoRepository?) {
        repository = repo
        if (repo != null) startLogCollection()
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
                    val current = _uiState.value.logs
                    // 保持最近 500 条
                    val updated = (current + log).takeLast(500)
                    _uiState.value = _uiState.value.copy(logs = updated)
                }
        }
    }

    fun setLevel(level: String) {
        _uiState.value = _uiState.value.copy(level = level, logs = emptyList())
        startLogCollection()
    }

    fun clearLogs() {
        _uiState.value = _uiState.value.copy(logs = emptyList())
    }

    override fun onCleared() {
        super.onCleared()
        logJob?.cancel()
    }
}
