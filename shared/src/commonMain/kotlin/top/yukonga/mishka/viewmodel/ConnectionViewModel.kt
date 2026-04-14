package top.yukonga.mishka.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import top.yukonga.mishka.data.model.ConnectionInfo
import top.yukonga.mishka.data.repository.MihomoRepository

data class ConnectionUiState(
    val connections: List<ConnectionInfo> = emptyList(),
    val downloadTotal: Long = 0,
    val uploadTotal: Long = 0,
    val searchQuery: String = "",
    val isConnected: Boolean = false,
    val error: String = "",
)

class ConnectionViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ConnectionUiState())
    val uiState: StateFlow<ConnectionUiState> = _uiState.asStateFlow()

    private var repository: MihomoRepository? = null
    private var connectionJob: Job? = null

    fun setRepository(repo: MihomoRepository?) {
        repository = repo
        if (repo != null) startConnectionCollection()
    }

    private fun startConnectionCollection() {
        connectionJob?.cancel()
        val repo = repository ?: return

        connectionJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isConnected = true)
            repo.connectionsFlow()
                .catch {
                    _uiState.value = _uiState.value.copy(isConnected = false)
                }
                .collect { response ->
                    _uiState.value = _uiState.value.copy(
                        connections = response.connections,
                        downloadTotal = response.downloadTotal,
                        uploadTotal = response.uploadTotal,
                    )
                }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun filteredConnections(): List<ConnectionInfo> {
        val state = _uiState.value
        val query = state.searchQuery.lowercase()
        if (query.isBlank()) return state.connections
        return state.connections.filter { conn ->
            conn.metadata.host.lowercase().contains(query) ||
                conn.metadata.process.lowercase().contains(query) ||
                conn.rule.lowercase().contains(query) ||
                conn.metadata.destinationIP.contains(query) ||
                conn.chains.any { it.lowercase().contains(query) }
        }
    }

    fun closeConnection(id: String) {
        val repo = repository ?: return
        viewModelScope.launch {
            repo.closeConnection(id)
        }
    }

    fun closeAllConnections() {
        val repo = repository ?: return
        viewModelScope.launch {
            repo.closeAllConnections()
        }
    }

    override fun onCleared() {
        super.onCleared()
        connectionJob?.cancel()
    }
}
