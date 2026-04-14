package top.yukonga.mishka.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.yukonga.mishka.data.model.DnsAnswer
import top.yukonga.mishka.data.repository.MihomoRepository

data class DnsQueryUiState(
    val queryName: String = "",
    val queryType: String = "A",
    val answers: List<DnsAnswer> = emptyList(),
    val status: Int? = null,
    val isQuerying: Boolean = false,
    val error: String = "",
)

class DnsQueryViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(DnsQueryUiState())
    val uiState: StateFlow<DnsQueryUiState> = _uiState.asStateFlow()

    private var repository: MihomoRepository? = null

    fun setRepository(repo: MihomoRepository?) {
        repository = repo
    }

    fun setQueryName(name: String) {
        _uiState.value = _uiState.value.copy(queryName = name)
    }

    fun setQueryType(type: String) {
        _uiState.value = _uiState.value.copy(queryType = type)
    }

    fun queryDns() {
        val repo = repository ?: return
        val state = _uiState.value
        if (state.queryName.isBlank() || state.isQuerying) return

        _uiState.value = state.copy(isQuerying = true, error = "", answers = emptyList(), status = null)

        viewModelScope.launch {
            repo.queryDns(state.queryName, state.queryType)
                .onSuccess { response ->
                    _uiState.value = _uiState.value.copy(
                        answers = response.Answer,
                        status = response.Status,
                        isQuerying = false,
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isQuerying = false,
                        error = "查询失败: ${it.message}",
                    )
                }
        }
    }

    fun flushDnsCache() {
        val repo = repository ?: return
        viewModelScope.launch {
            repo.flushDnsCache()
        }
    }

    fun flushFakeIp() {
        val repo = repository ?: return
        viewModelScope.launch {
            repo.flushFakeIp()
        }
    }
}
