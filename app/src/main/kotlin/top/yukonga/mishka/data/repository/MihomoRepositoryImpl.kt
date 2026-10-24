package top.yukonga.mishka.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import top.yukonga.mishka.data.api.MihomoApiClient
import top.yukonga.mishka.data.api.MihomoWebSocket
import top.yukonga.mishka.domain.model.ConnectionsResponse
import top.yukonga.mishka.domain.model.DelayResult
import top.yukonga.mishka.domain.model.DnsQueryResponse
import top.yukonga.mishka.domain.model.GroupsResponse
import top.yukonga.mishka.domain.model.LogMessage
import top.yukonga.mishka.domain.model.MemoryData
import top.yukonga.mishka.domain.model.MihomoConfig
import top.yukonga.mishka.domain.model.MihomoVersion
import top.yukonga.mishka.domain.model.ProvidersResponse
import top.yukonga.mishka.domain.model.ProxiesResponse
import top.yukonga.mishka.domain.model.RuleProvidersResponse
import top.yukonga.mishka.domain.model.RulesResponse
import top.yukonga.mishka.domain.model.TrafficData
import top.yukonga.mishka.domain.repository.MihomoRepository

class MihomoRepositoryImpl(
    private val apiClient: MihomoApiClient,
    private val webSocket: MihomoWebSocket,
) : MihomoRepository {
    // === 连接状态 ===

    override val connectionState: StateFlow<Boolean> get() = webSocket.connectionState

    // === 实时流 ===

    override fun trafficFlow(): Flow<TrafficData> = webSocket.trafficFlow()
    override fun logsFlow(level: String): Flow<LogMessage> = webSocket.logsFlow(level)
    override fun memoryFlow(): Flow<MemoryData> = webSocket.memoryFlow()

    // === REST API ===

    override suspend fun getVersion(): Result<MihomoVersion> = runCatching { apiClient.getVersion() }
    override suspend fun getConfig(): Result<MihomoConfig> = runCatching { apiClient.getConfig() }
    override suspend fun getProxies(): Result<ProxiesResponse> = runCatching { apiClient.getProxies() }
    override suspend fun getGroups(): Result<GroupsResponse> = runCatching { apiClient.getGroups() }
    override suspend fun selectProxy(group: String, name: String): Result<Unit> = runCatching { apiClient.selectProxy(group, name) }
    override suspend fun unfixProxy(group: String): Result<Unit> = runCatching { apiClient.unfixProxy(group) }
    override suspend fun getGroupDelay(
        name: String,
        testUrl: String,
        timeout: Int,
    ): Result<Map<String, Int>> =
        runCatching { apiClient.getGroupDelay(name, testUrl, timeout) }

    override suspend fun getProxyDelay(
        name: String,
        testUrl: String,
        timeout: Int,
    ): Result<DelayResult> =
        runCatching { apiClient.getProxyDelay(name, testUrl, timeout) }

    override suspend fun getProviderProxyDelay(
        provider: String,
        name: String,
        testUrl: String,
        timeout: Int,
    ): Result<DelayResult> =
        runCatching { apiClient.getProviderProxyDelay(provider, name, testUrl, timeout) }

    override suspend fun getRules(): Result<RulesResponse> = runCatching { apiClient.getRules() }
    override fun connectionsFlow(): Flow<ConnectionsResponse> = webSocket.connectionsFlow()
    override suspend fun getConnections(): Result<ConnectionsResponse> = runCatching { apiClient.getConnections() }
    override suspend fun closeAllConnections(): Result<Unit> = runCatching { apiClient.closeAllConnections() }
    override suspend fun closeConnection(id: String): Result<Unit> = runCatching { apiClient.closeConnection(id) }
    override suspend fun getProviders(): Result<ProvidersResponse> = runCatching { apiClient.getProviders() }
    override suspend fun updateProvider(name: String): Result<Unit> = runCatching { apiClient.updateProvider(name) }
    override suspend fun getRuleProviders(): Result<RuleProvidersResponse> =
        runCatching { apiClient.getRuleProviders() }

    override suspend fun updateRuleProvider(name: String): Result<Unit> = runCatching { apiClient.updateRuleProvider(name) }
    override suspend fun queryDns(name: String, type: String): Result<DnsQueryResponse> = runCatching { apiClient.queryDns(name, type) }
    override suspend fun updateSystemDns(servers: List<String>): Result<Unit> =
        runCatching { apiClient.updateSystemDns(servers) }
            .onFailure { if (it is CancellationException) throw it }

    override suspend fun flushFakeIp(): Result<Unit> = runCatching { apiClient.flushFakeIp() }
    override suspend fun flushDnsCache(): Result<Unit> = runCatching { apiClient.flushDnsCache() }

    override fun close() {
        apiClient.close()
        webSocket.close()
    }
}
