package top.yukonga.mishka.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.yukonga.mishka.data.model.ConnectionInfo
import top.yukonga.mishka.data.repository.MihomoRepository

@Immutable
data class ConnectionUiState(
    val connections: ImmutableList<ConnectionInfo> = persistentListOf(),
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
    private var connectionStateJob: Job? = null

    fun setRepository(repo: MihomoRepository?) {
        repository = repo
        connectionStateJob?.cancel()
        if (repo != null) {
            connectionStateJob = viewModelScope.launch {
                repo.connectionState.collect { connected ->
                    _uiState.value = _uiState.value.copy(isConnected = connected)
                }
            }
            startConnectionCollection()
        } else {
            connectionJob?.cancel()
            connectionJob = null
            connectionStateJob = null
            _uiState.value = ConnectionUiState()
        }
    }

    private fun startConnectionCollection() {
        connectionJob?.cancel()
        val repo = repository ?: return

        connectionJob = viewModelScope.launch {
            repo.connectionsFlow().collect { response ->
                _uiState.value = _uiState.value.copy(
                    connections = response.connections.toPersistentList(),
                    downloadTotal = response.downloadTotal,
                    uploadTotal = response.uploadTotal,
                )
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun filteredConnections(searchQuery: String = _uiState.value.searchQuery): ImmutableList<ConnectionInfo> {
        val query = searchQuery.lowercase()
        if (query.isBlank()) return _uiState.value.connections
        return _uiState.value.connections.filter { conn ->
            conn.metadata.host.lowercase().contains(query) ||
                conn.metadata.process.lowercase().contains(query) ||
                conn.rule.lowercase().contains(query) ||
                conn.metadata.destinationIP.contains(query) ||
                conn.chains.any { it.lowercase().contains(query) }
        }.toPersistentList()
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
        connectionStateJob?.cancel()
    }
}
