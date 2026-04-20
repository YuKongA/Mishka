package top.yukonga.mishka.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import kotlinx.coroutines.flow.StateFlow

actual class ProxyServiceController(private val context: Context) {

    private val storage by lazy { PlatformStorage(context) }

    actual val status: StateFlow<ProxyServiceStatus> = ProxyServiceBridge.state

    actual fun start(subscriptionId: String?) {
        val mode = getTunMode()
        val intent = buildServiceIntent(mode, "START")
        subscriptionId?.let { intent.putExtra("subscription_id", it) }
        context.startForegroundService(intent)
    }

    actual fun restart(subscriptionId: String?) {
        // 优先读 bridge：代理运行中时它反映实际 Service；否则读 storage（用户最新选择）
        val mode = activeModeOrStored()
        val intent = buildServiceIntent(mode, "RESTART")
        subscriptionId?.let { intent.putExtra("subscription_id", it) }
        context.startService(intent)
    }

    actual fun stop() {
        val mode = activeModeOrStored()
        val intent = buildServiceIntent(mode, "STOP")
        context.startService(intent)
    }

    /**
     * 解析当前应该操作的 TunMode：
     * - 代理正在 Running/Starting/Stopping 时，用 bridge 的 tunMode（对应实际在跑的 Service）
     * - 否则用 storage 里的当前选择（用户可能刚改但还没启动）
     */
    private fun activeModeOrStored(): TunMode {
        val bridge = ProxyServiceBridge.state.value
        return if (bridge.state != ProxyState.Stopped && bridge.state != ProxyState.Error) {
            bridge.tunMode
        } else {
            getTunMode()
        }
    }

    /**
     * 组装指向目标 Service 的 Intent。ROOT_TUN / ROOT_TPROXY 共用 MishkaRootService，
     * 通过 EXTRA_SUBMODE 区分内部分支。
     */
    private fun buildServiceIntent(mode: TunMode, op: String): Intent {
        val (className, action, submode) = when (mode) {
            TunMode.Vpn -> Triple(
                "top.yukonga.mishka.service.MishkaTunService",
                "top.yukonga.mishka.$op",
                null,
            )

            TunMode.RootTun -> Triple(
                "top.yukonga.mishka.service.MishkaRootService",
                "top.yukonga.mishka.ROOT_$op",
                "tun",
            )

            TunMode.RootTproxy -> Triple(
                "top.yukonga.mishka.service.MishkaRootService",
                "top.yukonga.mishka.ROOT_$op",
                "tproxy",
            )
        }
        return Intent().apply {
            setClassName(context.packageName, className)
            this.action = action
            submode?.let { putExtra("submode", it) }
        }
    }

    actual fun requestVpnPermission() {
        if (getTunMode() != TunMode.Vpn) return
        val intent = VpnService.prepare(context)
        if (intent != null && context is Activity) {
            context.startActivityForResult(intent, VPN_REQUEST_CODE)
        }
    }

    actual fun hasVpnPermission(): Boolean {
        if (getTunMode() != TunMode.Vpn) return true
        return VpnService.prepare(context) == null
    }

    actual fun hasRootPermission(): Boolean {
        return storage.getString(StorageKeys.HAS_ROOT, "false") == "true"
    }

    actual fun getTunMode(): TunMode {
        val raw = storage.getString(StorageKeys.TUN_MODE, "vpn")
        // 旧版 "root" 迁移为 "root_tun"
        val normalized = if (raw == "root") {
            storage.putString(StorageKeys.TUN_MODE, "root_tun")
            "root_tun"
        } else raw
        return when (normalized) {
            "root_tun" -> TunMode.RootTun
            "root_tproxy" -> TunMode.RootTproxy
            else -> TunMode.Vpn
        }
    }

    actual fun verifyAndSyncState() {
        val wasRunning = storage.getString(StorageKeys.SERVICE_WAS_RUNNING, "false") == "true"
        val bridgeState = ProxyServiceBridge.state.value.state

        // bridge 认为在运行，但 VPN 权限已丢失（被其他 VPN 顶替），纠正状态
        if (bridgeState == ProxyState.Running || bridgeState == ProxyState.Starting) {
            if (getTunMode() == TunMode.Vpn && !hasVpnPermission()) {
                storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
                ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopped))
            }
            return
        }

        // 卡在 Stopping 状态（异步块被取消的残留场景），直接重置
        if (bridgeState == ProxyState.Stopping) {
            storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
            ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopped))
            return
        }

        val currentMode = getTunMode()
        // ROOT 模式：app 被杀后 mihomo 进程仍存活，重新打开时尝试重连
        if (wasRunning && (currentMode == TunMode.RootTun || currentMode == TunMode.RootTproxy)) {
            val hasPid = storage.getString(StorageKeys.ROOT_MIHOMO_PID, "").isNotEmpty()
            if (hasPid) {
                val subscriptionId = storage.getString(StorageKeys.ACTIVE_PROFILE_UUID, "").ifEmpty { null }
                start(subscriptionId)
                return
            }
            storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
        }

        if (wasRunning && currentMode == TunMode.Vpn) {
            storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
        }
    }

    companion object {
        const val VPN_REQUEST_CODE = 1001
    }
}
