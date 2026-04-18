package top.yukonga.mishka.data.repository

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import top.yukonga.mishka.data.model.Subscription

/**
 * 订阅 HTTP 下载。
 * - User-Agent 由调用方注入（典型 `ClashMetaForAndroid/{version}` —— 订阅服务白名单匹配）
 * - 强制状态码与空 body 检查 → 抛 [ImportError]
 * - 不在客户端转换格式，原始 YAML 直接交给 mihomo 解析（V2Ray base64 订阅须由用户用 proxy-providers 引用）
 */
class SubscriptionFetcher(private val userAgent: String) {

    private val client = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 60_000
        }
    }

    /**
     * 下载订阅配置并返回更新后的 Subscription 信息和配置内容。
     */
    suspend fun fetch(subscription: Subscription): FetchResult {
        val response: HttpResponse = client.get(subscription.url) {
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

        if (userInfo != null) {
            userInfo.split(";").map { it.trim() }.forEach { part ->
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
            updatedAt = System.currentTimeMillis(),
        )

        return FetchResult(updatedSub, configContent)
    }

    /**
     * 解析数值，处理浮点数（只取整数部分）
     */
    private fun parseNumericValue(value: String): Long? {
        if (value.isBlank()) return null
        val intPart = value.split(".").first()
        return intPart.toLongOrNull()
    }

    fun close() {
        client.close()
    }
}

data class FetchResult(
    val subscription: Subscription,
    val configContent: String,
)
