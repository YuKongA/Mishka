package top.yukonga.mishka.data.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import top.yukonga.mishka.data.model.Subscription
import kotlin.time.Clock

/**
 * 订阅 HTTP 下载。
 * - User-Agent 由调用方注入（典型 `ClashMetaForAndroid/{version}` —— 订阅服务白名单匹配）
 * - 强制状态码与空 body 检查 → 抛 [ImportError]
 * - 不在客户端转换格式，原始 YAML 直接交给 mihomo 解析（V2Ray base64 订阅须由用户用 proxy-providers 引用）
 * - proxyUrlProvider 返回非 null 时，先尝试走代理；代理失败自动 fallback 直连。
 *   避免代理节点炸/规则不对时用户陷入"connection closed"彻底失败。
 */
class SubscriptionFetcher(
    private val userAgent: String,
    private val proxyUrlProvider: suspend () -> String? = { null },
) {

    /**
     * 下载订阅配置并返回更新后的 Subscription 信息和配置内容。
     *
     * 策略：proxyUrlProvider 返回 URL 时先试代理，遇到非业务错误（网络层异常）自动重试直连；
     * ImportError（业务错误：HTTP 状态码、空 body 等）和 CancellationException 不重试。
     */
    suspend fun fetch(subscription: Subscription): FetchResult {
        val proxyUrl = proxyUrlProvider()
        if (proxyUrl != null) {
            try {
                return fetchOnce(subscription, proxyUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImportError) {
                throw e
            } catch (_: Throwable) {
                // 网络层异常（connection closed / timeout / handshake failed / ...）：
                // 代理链路问题，回退直连一次
            }
        }
        return fetchOnce(subscription, null)
    }

    /**
     * 单次下载。每次创建独立 HttpClient 按需配置 HTTP 代理，finally 关闭。
     */
    private suspend fun fetchOnce(subscription: Subscription, proxyUrl: String?): FetchResult {
        val client = HttpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 30_000
                requestTimeoutMillis = 60_000
                socketTimeoutMillis = 60_000
            }
            if (proxyUrl != null) {
                engine {
                    proxy = ProxyBuilder.http(Url(proxyUrl))
                }
            }
        }

        client.use { c ->
            val response: HttpResponse = c.get(subscription.url) {
                header("User-Agent", userAgent)
            }

            if (!response.status.isSuccess()) {
                throw ImportError.HttpStatus(response.status.value, response.status.description)
            }

            val configContent = response.bodyAsText()
            if (configContent.isBlank()) throw ImportError.EmptyBody()

            // 解析 subscription-userinfo 头
            // 格式: upload=xxx; download=xxx; total=xxx; expire=xxx
            val userInfo = response.headers["subscription-userinfo"] ?: response.headers["Subscription-Userinfo"]
            var upload = subscription.upload
            var download = subscription.download
            var total = subscription.total
            var expire = subscription.expire

            userInfo?.split(";")?.map { it.trim() }?.forEach { part ->
                val parts = part.split("=", limit = 2).map { it.trim() }
                if (parts.size != 2) return@forEach
                val key = parts[0]
                val value = parts[1]
                when {
                    key.contains("upload") -> upload = parseNumericValue(value) ?: upload
                    key.contains("download") -> download = parseNumericValue(value) ?: download
                    key.contains("total") -> total = parseNumericValue(value) ?: total
                    key.contains("expire") -> {
                        // expire 是 Unix 秒级时间戳，转为毫秒
                        val seconds = value.toDoubleOrNull()
                        if (seconds != null) {
                            expire = (seconds * 1000).toLong()
                        }
                    }
                }
            }

            // 从 content-disposition 或 profile-title 获取名称
            val profileName = response.headers["profile-title"]
                ?: response.headers["content-disposition"]
                    ?.let { Regex("filename=\"?(.+?)\"?$").find(it)?.groupValues?.get(1) }

            val updatedSub = subscription.copy(
                name = if (profileName != null && subscription.name.isBlank()) profileName else subscription.name,
                upload = upload,
                download = download,
                total = total,
                expire = expire,
                updatedAt = Clock.System.now().toEpochMilliseconds(),
            )

            return FetchResult(updatedSub, configContent)
        }
    }

    /**
     * 解析数值，处理浮点数（只取整数部分）
     */
    private fun parseNumericValue(value: String): Long? {
        if (value.isBlank()) return null
        val intPart = value.split(".").first()
        return intPart.toLongOrNull()
    }
}

data class FetchResult(
    val subscription: Subscription,
    val configContent: String,
)
