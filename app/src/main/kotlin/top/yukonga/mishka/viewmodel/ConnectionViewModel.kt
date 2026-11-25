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
import top.yukonga.mishka.domain.model.ConnectionInfo
import top.yukonga.mishka.domain.repository.MihomoRepository
import top.yukonga.mishka.util.SearchMatcher
import top.yukonga.mishka.util.SearchQuery

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

    /** 连接页是否在前台。VM 是进程级 single，无人观看时不该订阅全量列表 */
    private var observing = false

    fun setRepository(repo: MihomoRepository?) {
        if (repository === repo) return
        repository = repo
        connectionStateJob?.cancel()
        connectionJob?.cancel()
        connectionJob = null
        if (repo == null) {
            connectionStateJob = null
            _uiState.value = ConnectionUiState(searchQuery = _uiState.value.searchQuery)
            return
        }
        connectionStateJob = viewModelScope.launch {
            repo.connectionState.collect { connected ->
                _uiState.value = _uiState.value.copy(isConnected = connected)
            }
        }
        if (observing) startConnectionCollection()
    }

    /**
     * 订阅 `/connections`。**只在连接页可见期间调用**——该流每秒推全量连接列表，
     * 数百条时每帧都要反序列化并重建列表，常驻代价不小。
     */
    fun startObserving() {
        if (observing) return
        observing = true
        startConnectionCollection()
    }

    fun stopObserving() {
        observing = false
        connectionJob?.cancel()
        connectionJob = null
        _uiState.value = _uiState.value.copy(connections = persistentListOf())
    }

    private fun startConnectionCollection() {
        connectionJob?.cancel()
        val repo = repository ?: return

        connectionJob = viewModelScope.launch {
            repo.connectionsFlow().collect { response ->
                // 上游带缓冲，cancel 之外还需身份校验，否则旧 repo 的滞留帧会覆盖新数据
                if (repository !== repo) return@collect
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
        val parsedQuery = SearchQuery.parse(searchQuery)
        if (parsedQuery.isEmpty) return _uiState.value.connections
        return SearchMatcher.rank(
            items = _uiState.value.connections,
            query = parsedQuery,
        ) { conn ->
            val meta = conn.metadata
            val primaryHost = meta.host.ifEmpty { "${meta.destinationIP}:${meta.destinationPort}" }
            field(primaryHost, allowAcronym = true)
            field(meta.process, allowAcronym = true)
            fields(conn.chains)
            field(conn.rulePayload)
            field(conn.rule)
            field(meta.network)
            if (meta.host.isNotEmpty()) field(meta.destinationIP)
            field(meta.destinationPort)
            field(meta.sourceIP)
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
}
