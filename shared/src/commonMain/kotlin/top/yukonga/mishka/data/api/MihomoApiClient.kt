package top.yukonga.mishka.data.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import top.yukonga.mishka.data.model.ConfigPatch
import top.yukonga.mishka.data.model.ConnectionsResponse
import top.yukonga.mishka.data.model.DelayResult
import top.yukonga.mishka.data.model.DnsQueryResponse
import top.yukonga.mishka.data.model.GroupsResponse
import top.yukonga.mishka.data.model.MihomoConfig
import top.yukonga.mishka.data.model.MihomoVersion
import top.yukonga.mishka.data.model.ProvidersResponse
import top.yukonga.mishka.data.model.ProxiesResponse
import top.yukonga.mishka.data.model.ProxyNode
import top.yukonga.mishka.data.model.RuleProvidersResponse
import top.yukonga.mishka.data.model.RulesResponse

class MihomoApiClient(
    private val baseUrl: String = "http://127.0.0.1:9090",
    private val secret: String = "",
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        install(WebSockets)
        defaultRequest {
            if (secret.isNotEmpty()) {
                header("Authorization", "Bearer $secret")
            }
        }
    }

    // === 版本 ===

    suspend fun getVersion(): MihomoVersion =
        client.get("$baseUrl/version").body()

    // === 配置 ===

    suspend fun getConfig(): MihomoConfig =
        client.get("$baseUrl/configs").body()

    suspend fun patchConfig(patch: ConfigPatch) {
        client.patch("$baseUrl/configs") {
            contentType(ContentType.Application.Json)
            setBody(patch)
        }
    }

    suspend fun reloadConfig(path: String, payload: String = "") {
        client.put("$baseUrl/configs") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("path" to path, "payload" to payload))
        }
    }

    // === 代理 ===

    suspend fun getProxies(): ProxiesResponse =
        client.get("$baseUrl/proxies").body()

    suspend fun getGroups(): GroupsResponse =
        client.get("$baseUrl/group").body()

    suspend fun getProxy(name: String): ProxyNode =
        client.get("$baseUrl/proxies/$name").body()

    suspend fun selectProxy(group: String, name: String) {
        client.put("$baseUrl/proxies/$group") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("name" to name))
        }
    }

    suspend fun getProxyDelay(name: String, testUrl: String = "http://www.gstatic.com/generate_204", timeout: Int = 5000): DelayResult =
        client.get("$baseUrl/proxies/$name/delay") {
            url {
                parameters.append("url", testUrl)
                parameters.append("timeout", timeout.toString())
            }
        }.body()

    /**
     * 触发代理组的健康检查（url-test/fallback 组会自动更新最优节点）
     */
    suspend fun healthCheck(providerName: String) {
        client.get("$baseUrl/providers/proxies/$providerName/healthcheck")
    }

    // === 规则 ===

    suspend fun getRules(): RulesResponse =
        client.get("$baseUrl/rules").body()

    // === 连接 ===

    suspend fun getConnections(): ConnectionsResponse =
        client.get("$baseUrl/connections").body()

    suspend fun closeAllConnections() {
        client.delete("$baseUrl/connections")
    }

    suspend fun closeConnection(id: String) {
        client.delete("$baseUrl/connections/$id")
    }

    // === Provider ===

    suspend fun getProviders(): ProvidersResponse =
        client.get("$baseUrl/providers/proxies").body()

    suspend fun updateProvider(name: String) {
        client.put("$baseUrl/providers/proxies/$name")
    }

    // === Rule Provider ===

    suspend fun getRuleProviders(): RuleProvidersResponse =
        client.get("$baseUrl/providers/rules").body()

    suspend fun updateRuleProvider(name: String) {
        client.put("$baseUrl/providers/rules/$name")
    }

    // === DNS ===

    suspend fun queryDns(name: String, type: String = "A"): DnsQueryResponse =
        client.get("$baseUrl/dns/query") {
            url {
                parameters.append("name", name)
                parameters.append("type", type)
            }
        }.body()

    // === 缓存 ===

    suspend fun flushFakeIp() {
        client.post("$baseUrl/cache/fakeip/flush")
    }

    suspend fun flushDnsCache() {
        client.post("$baseUrl/cache/dns/flush")
    }

    // === 控制 ===

    suspend fun restart() {
        client.post("$baseUrl/restart")
    }

    // === 生命周期 ===

    fun close() {
        client.close()
    }

    fun getWebSocketUrl(path: String): String {
        val wsBase = baseUrl.replace("http://", "ws://").replace("https://", "wss://")
        return if (secret.isNotEmpty()) {
            "$wsBase$path?token=$secret"
        } else {
            "$wsBase$path"
        }
    }
}
