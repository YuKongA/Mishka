package top.yukonga.mishka.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import kotlinx.coroutines.flow.StateFlow

actual class ProxyServiceController(private val context: Context) {

    actual val status: StateFlow<ProxyServiceStatus> = ProxyServiceBridge.state

    actual fun start(subscriptionId: String?) {
        val intent = Intent().apply {
            setClassName(context.packageName, "top.yukonga.mishka.service.MishkaTunService")
            action = "top.yukonga.mishka.START"
            subscriptionId?.let { putExtra("subscription_id", it) }
        }
        context.startForegroundService(intent)
    }

    actual fun stop() {
        val intent = Intent().apply {
            setClassName(context.packageName, "top.yukonga.mishka.service.MishkaTunService")
            action = "top.yukonga.mishka.STOP"
        }
        context.startService(intent)
    }

    actual fun requestVpnPermission() {
        val intent = VpnService.prepare(context)
        if (intent != null && context is Activity) {
            context.startActivityForResult(intent, VPN_REQUEST_CODE)
        }
    }

    actual fun hasVpnPermission(): Boolean {
        return VpnService.prepare(context) == null
    }

    companion object {
        const val VPN_REQUEST_CODE = 1001
    }
}
