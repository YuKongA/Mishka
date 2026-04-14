package top.yukonga.mishka.data.repository

import kotlinx.coroutines.flow.Flow
import top.yukonga.mishka.data.api.MihomoApiClient
import top.yukonga.mishka.data.api.MihomoWebSocket
import top.yukonga.mishka.data.model.ConfigPatch
import top.yukonga.mishka.data.model.ConnectionsResponse
import top.yukonga.mishka.data.model.DelayResult
import top.yukonga.mishka.data.model.DnsQueryResponse
import top.yukonga.mishka.data.model.LogMessage
import top.yukonga.mishka.data.model.MemoryData
import top.yukonga.mishka.data.model.MihomoConfig
import top.yukonga.mishka.data.model.MihomoVersion
import top.yukonga.mishka.data.model.ProxiesResponse
import top.yukonga.mishka.data.model.ProvidersResponse
import top.yukonga.mishka.data.model.RulesResponse
import top.yukonga.mishka.data.model.TrafficData

class MihomoRepository(
    private val apiClient: MihomoApiClient,
    private val webSocket: MihomoWebSocket,
) {
    // === 实时流 ===

    fun trafficFlow(): Flow<TrafficData> = webSocket.trafficFlow()
    fun logsFlow(level: String = "info"): Flow<LogMessage> = webSocket.logsFlow(level)
    fun memoryFlow(): Flow<MemoryData> = webSocket.memoryFlow()

    // === REST API ===

    suspend fun getVersion(): Result<MihomoVersion> = runCatching { apiClient.getVersion() }
    suspend fun getConfig(): Result<MihomoConfig> = runCatching { apiClient.getConfig() }
    suspend fun patchConfig(patch: ConfigPatch): Result<Unit> = runCatching { apiClient.patchConfig(patch) }
    suspend fun reloadConfig(path: String): Result<Unit> = runCatching { apiClient.reloadConfig(path) }
    suspend fun getProxies(): Result<ProxiesResponse> = runCatching { apiClient.getProxies() }
    suspend fun getGroups(): Result<top.yukonga.mishka.data.model.GroupsResponse> = runCatching { apiClient.getGroups() }
    suspend fun selectProxy(group: String, name: String): Result<Unit> = runCatching { apiClient.selectProxy(group, name) }
    suspend fun getProxyDelay(name: String, testUrl: String = "http://www.gstatic.com/generate_204", timeout: Int = 5000): Result<DelayResult> =
        runCatching { apiClient.getProxyDelay(name, testUrl, timeout) }
    suspend fun healthCheck(providerName: String): Result<Unit> = runCatching { apiClient.healthCheck(providerName) }
    suspend fun getRules(): Result<RulesResponse> = runCatching { apiClient.getRules() }
    fun connectionsFlow(): Flow<ConnectionsResponse> = webSocket.connectionsFlow()
    suspend fun getConnections(): Result<ConnectionsResponse> = runCatching { apiClient.getConnections() }
    suspend fun closeAllConnections(): Result<Unit> = runCatching { apiClient.closeAllConnections() }
    suspend fun closeConnection(id: String): Result<Unit> = runCatching { apiClient.closeConnection(id) }
    suspend fun getProviders(): Result<ProvidersResponse> = runCatching { apiClient.getProviders() }
    suspend fun updateProvider(name: String): Result<Unit> = runCatching { apiClient.updateProvider(name) }
    suspend fun getRuleProviders(): Result<top.yukonga.mishka.data.model.RuleProvidersResponse> = runCatching { apiClient.getRuleProviders() }
    suspend fun updateRuleProvider(name: String): Result<Unit> = runCatching { apiClient.updateRuleProvider(name) }
    suspend fun queryDns(name: String, type: String = "A"): Result<DnsQueryResponse> = runCatching { apiClient.queryDns(name, type) }
    suspend fun flushFakeIp(): Result<Unit> = runCatching { apiClient.flushFakeIp() }
    suspend fun flushDnsCache(): Result<Unit> = runCatching { apiClient.flushDnsCache() }
    suspend fun restart(): Result<Unit> = runCatching { apiClient.restart() }

    fun close() {
        apiClient.close()
        webSocket.close()
    }
}
