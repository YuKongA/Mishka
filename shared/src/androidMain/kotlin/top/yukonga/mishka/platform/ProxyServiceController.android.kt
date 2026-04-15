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
        val (className, action) = when (getTunMode()) {
            TunMode.Root -> "top.yukonga.mishka.service.MishkaRootService" to "top.yukonga.mishka.ROOT_START"
            TunMode.Vpn -> "top.yukonga.mishka.service.MishkaTunService" to "top.yukonga.mishka.START"
        }
        val intent = Intent().apply {
            setClassName(context.packageName, className)
            this.action = action
            subscriptionId?.let { putExtra("subscription_id", it) }
        }
        context.startForegroundService(intent)
    }

    actual fun stop() {
        val (className, action) = when (ProxyServiceBridge.state.value.tunMode) {
            TunMode.Root -> "top.yukonga.mishka.service.MishkaRootService" to "top.yukonga.mishka.ROOT_STOP"
            TunMode.Vpn -> "top.yukonga.mishka.service.MishkaTunService" to "top.yukonga.mishka.STOP"
        }
        val intent = Intent().apply {
            setClassName(context.packageName, className)
            this.action = action
        }
        context.startService(intent)
    }

    actual fun requestVpnPermission() {
        if (getTunMode() == TunMode.Root) return
        val intent = VpnService.prepare(context)
        if (intent != null && context is Activity) {
            context.startActivityForResult(intent, VPN_REQUEST_CODE)
        }
    }

    actual fun hasVpnPermission(): Boolean {
        if (getTunMode() == TunMode.Root) return true
        return VpnService.prepare(context) == null
    }

    actual fun hasRootPermission(): Boolean {
        return storage.getString(StorageKeys.HAS_ROOT, "false") == "true"
    }

    actual fun getTunMode(): TunMode {
        val mode = storage.getString(StorageKeys.TUN_MODE, "vpn")
        return if (mode == "root") TunMode.Root else TunMode.Vpn
    }

    actual fun verifyAndSyncState() {
        val wasRunning = storage.getString(StorageKeys.SERVICE_WAS_RUNNING, "false") == "true"
        val bridgeState = ProxyServiceBridge.state.value.state

        if (bridgeState == ProxyState.Running || bridgeState == ProxyState.Starting) return

        if (wasRunning && getTunMode() == TunMode.Vpn) {
            storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
        }
    }

    companion object {
        const val VPN_REQUEST_CODE = 1001
    }
}
