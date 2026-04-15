package top.yukonga.mishka.data.repository

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import top.yukonga.mishka.data.model.Subscription

class SubscriptionFetcher {

    private val client = HttpClient()

    /**
     * 下载订阅配置并返回更新后的 Subscription 信息和配置内容。
     */
    suspend fun fetch(subscription: Subscription): FetchResult {
        val response: HttpResponse = client.get(subscription.url) {
            header("User-Agent", "Mishka/1.0 (mihomo)")
        }

        val configContent = response.bodyAsText()

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
     * 解析数值，处理浮点数（只取整数部分，参考 CMFA）
     */
    private fun parseNumericValue(value: String): Long? {
        if (value.isBlank()) return null
        // 移除小数部分
        val intPart = value.split(".").first()
        return intPart.toLongOrNull()
    }

    /**
     * 下载指定 URL 的内容并返回字节数组，供 ConfigProcessor 预下载 provider 使用。
     */
    suspend fun downloadBytes(url: String): ByteArray {
        val response = client.get(url) {
            header("User-Agent", "Mishka/1.0 (mihomo)")
        }
        return response.body()
    }

    fun close() {
        client.close()
    }
}

data class FetchResult(
    val subscription: Subscription,
    val configContent: String,
)
