package top.yukonga.mishka.data.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPath
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import top.yukonga.mishka.domain.model.ConnectionsResponse
import top.yukonga.mishka.domain.model.DelayResult
import top.yukonga.mishka.domain.model.DnsQueryResponse
import top.yukonga.mishka.domain.model.GroupsResponse
import top.yukonga.mishka.domain.model.MihomoConfig
import top.yukonga.mishka.domain.model.MihomoVersion
import top.yukonga.mishka.domain.model.ProvidersResponse
import top.yukonga.mishka.domain.model.ProxiesResponse
import top.yukonga.mishka.domain.model.RuleProvidersResponse
import top.yukonga.mishka.domain.model.RulesResponse

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
        // mihomo 在 localhost，正常响应 ms 级；provider refresh 内含远端 HTTP 拉取，
        // 给 60s 足够慢网络完成。WebSockets 插件走独立心跳，不受这里影响。
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 60_000
        }
        defaultRequest {
            if (secret.isNotEmpty()) {
                header("Authorization", "Bearer $secret")
            }
        }
    }

    suspend fun getVersion(): MihomoVersion =
        client.get("$baseUrl/version").body()

    suspend fun getConfig(): MihomoConfig =
        client.get("$baseUrl/configs").body()

    // === 代理 ===

    suspend fun getProxies(): ProxiesResponse =
        client.get("$baseUrl/proxies").body()

    suspend fun getGroups(): GroupsResponse =
        client.get("$baseUrl/group").body()

    suspend fun selectProxy(group: String, name: String) {
        val response: HttpResponse = client.put("$baseUrl/proxies/${pathSegment(group)}") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("name" to name))
        }
        ensureSuccess(response, "proxy group '$group'")
    }

    /**
     * 解除 URLTest / Fallback 组的固定选择，交还给自动择优。
     * Selector 组无固定态，mihomo 对其返回 400。
     */
    suspend fun unfixProxy(group: String) {
        val response: HttpResponse = client.delete("$baseUrl/proxies/${pathSegment(group)}")
        ensureSuccess(response, "proxy group '$group'")
    }

    suspend fun getProxyDelay(name: String, testUrl: String = "http://www.gstatic.com/generate_204", timeout: Int = 5000): DelayResult =
        client.get("$baseUrl/proxies/${pathSegment(name)}/delay") {
            url {
                parameters.append("url", testUrl)
                parameters.append("timeout", timeout.toString())
            }
        }.body()

    /**
     * 测试 proxy-provider 内单节点延迟。provider 节点不在 /proxies 命名空间，
     * /proxies/{name}/delay 对其返回 404，必须走 provider 专属 healthcheck 端点。
     */
    suspend fun getProviderProxyDelay(
        provider: String,
        name: String,
        testUrl: String = "http://www.gstatic.com/generate_204",
        timeout: Int = 5000,
    ): DelayResult =
        client.get("$baseUrl/providers/proxies/${pathSegment(provider)}/${pathSegment(name)}/healthcheck") {
            url {
                parameters.append("url", testUrl)
                parameters.append("timeout", timeout.toString())
            }
        }.body()

    suspend fun getRules(): RulesResponse =
        client.get("$baseUrl/rules").body()

    // === 连接 ===

    suspend fun getConnections(): ConnectionsResponse =
        client.get("$baseUrl/connections").body()

    suspend fun closeAllConnections() {
        client.delete("$baseUrl/connections")
    }

    suspend fun closeConnection(id: String) {
        client.delete("$baseUrl/connections/${pathSegment(id)}")
    }

    // === Provider ===

    suspend fun getProviders(): ProvidersResponse =
        client.get("$baseUrl/providers/proxies").body()

    /**
     * 触发 proxy provider 重新拉取。mihomo 成功返回 204 No Content，
     * 拉取失败（network / parse）返回 503 + 错误 JSON body，provider 不存在返回 404。
     * Ktor 默认不对非 2xx 抛异常，必须显式校验 status，否则 runCatching 会吞掉 503
     * 让 UI 误以为刷新成功。
     */
    suspend fun updateProvider(name: String) {
        val response: HttpResponse = client.put("$baseUrl/providers/proxies/${pathSegment(name)}")
        ensureSuccess(response, "proxy provider '$name'")
    }

    // === Rule Provider ===

    suspend fun getRuleProviders(): RuleProvidersResponse =
        client.get("$baseUrl/providers/rules").body()

    /**
     * 触发 rule provider 重新拉取。语义同 [updateProvider]，但路由到 /providers/rules/。
     */
    suspend fun updateRuleProvider(name: String) {
        val response: HttpResponse = client.put("$baseUrl/providers/rules/${pathSegment(name)}")
        ensureSuccess(response, "rule provider '$name'")
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

    // === 生命周期 ===

    fun close() {
        client.close()
    }

    /**
     * 路径段只编码一次，并且必须编码 `/`。
     * chi 的 `/{name}` 只匹配一段；斜杠不编码时请求被切开，中间件用第一段查找，
     * 回 404 Resource not found。内核 getEscapeParam 会再 PathUnescape 一次，
     * 预编码或编两次都会对不上注册名。
     */
    private fun pathSegment(raw: String): String = raw.encodeURLPath(encodeSlash = true)

    /**
     * mihomo 返回的 JSON 错误体格式：`{"message": "..."}`。非 2xx 时抛带上下文的异常，
     * 让 runCatching 能区分真实成功 vs mihomo 返回的业务错误（典型 503 / 404）。
     */
    private suspend fun ensureSuccess(response: HttpResponse, context: String) {
        if (response.status.isSuccess()) return
        val detail = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
        val summary = extractErrorMessage(detail) ?: response.status.description
        throw MihomoApiException("$context: ${response.status.value} $summary")
    }

    private fun extractErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val element = json.parseToJsonElement(body)
            element.let { it as? kotlinx.serialization.json.JsonObject }
                ?.get("message")
                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                ?.content
        }.getOrNull()
    }

    /**
     * `secret` 只能走 query——mihomo 的 WS 分支只认 `?token=`，没有 REST 那样的 `Authorization` 头可回落。
     * 必须百分号编码：`&` 会被当 query 分隔符截断、`#` 之后整段不发送、`+` 解码成空格，
     * 任一都让服务端鉴权失败回 401，而 WS 握手失败是静默重连（内存/网速一直停在占位值）。
     */
    fun getWebSocketUrl(path: String): String {
        val wsBase = baseUrl.replace("http://", "ws://").replace("https://", "wss://")
        return if (secret.isNotEmpty()) {
            val separator = if ("?" in path) "&" else "?"
            "$wsBase$path${separator}token=${secret.encodeURLParameter()}"
        } else {
            "$wsBase$path"
        }
    }
}

class MihomoApiException(message: String) : Exception(message)
