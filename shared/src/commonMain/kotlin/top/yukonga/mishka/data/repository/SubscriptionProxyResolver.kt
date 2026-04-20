package top.yukonga.mishka.data.repository

import top.yukonga.mishka.data.api.MihomoApiClient
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyServiceStatus
import top.yukonga.mishka.platform.ProxyState
import top.yukonga.mishka.platform.StorageKeys

/**
 * 订阅下载代理 URL 解析器。
 *
 * 返回 `http://127.0.0.1:<mixed-port>` 或 null（不走代理）。
 * 走代理的前提：① 用户开关启用 ② mihomo 正在运行 ③ mixed-port 能解析出来。
 *
 * 不做 cache：用户可能中途改 override 或重启代理，每次 resolve 保持新鲜。
 * apiClient 在每次 resolve 按运行中的 bridge state 动态构造（external-controller + secret
 * 会随代理重启漂移），查完即 close。
 */
class SubscriptionProxyResolver(
    private val storage: PlatformStorage,
    private val overrideStore: OverrideJsonStore,
) {
    suspend fun resolve(): String? {
        val enabled = storage.getString(StorageKeys.SUBSCRIPTION_UPDATE_VIA_PROXY, "true") == "true"
        if (!enabled) return null
        val bridge = ProxyServiceBridge.state.value
        if (bridge.state != ProxyState.Running) return null

        // 优先用户 override 显式配置的 mixed-port，其次通过 mihomo API 实时查
        val port = overrideStore.load().mixedPort
            ?: queryMixedPortFromApi(bridge)
        if (port == null || port <= 0) return null
        return "http://127.0.0.1:$port"
    }

    private suspend fun queryMixedPortFromApi(bridge: ProxyServiceStatus): Int? {
        val baseUrl = "http://${bridge.externalController}"
        val client = MihomoApiClient(baseUrl = baseUrl, secret = bridge.secret)
        return try {
            client.getConfig().mixedPort.takeIf { it > 0 }
        } catch (_: Throwable) {
            null
        } finally {
            client.close()
        }
    }
}
