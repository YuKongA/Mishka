package top.yukonga.mishka.platform

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

actual class WifiPolicyController actual constructor(private val context: PlatformContext) {

    actual fun hasRequiredPermission(): Boolean = AndroidWifiPolicy.hasRequiredPermission(context)

    actual fun currentSsid(): String? = AndroidWifiPolicy.currentSsid(context)

    actual fun startMonitor() {
        setBootReceiverEnabled(true)
        context.startForegroundService(buildIntent(WifiPolicyIntents.ACTION_START))
    }

    actual fun stopMonitor() {
        setBootReceiverEnabled(false)
        context.startService(buildIntent(WifiPolicyIntents.ACTION_STOP))
    }

    actual fun evaluateNow() {
        context.startForegroundService(buildIntent(WifiPolicyIntents.ACTION_EVALUATE))
    }

    private fun buildIntent(action: String): Intent = Intent().apply {
        setClassName(context.packageName, "top.yukonga.mishka.service.WifiPolicyMonitorService")
        this.action = action
    }

    // Boot receiver 默认 disabled，随功能开关动态启用，避免功能未用时每次开机都被拉起
    private fun setBootReceiverEnabled(enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context.packageName, "top.yukonga.mishka.service.WifiPolicyBootReceiver"),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}

object AndroidWifiPolicy {
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasRequiredPermission(context: Context): Boolean =
        requiredPermissions().all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * [callbackSsid] 是带 FLAG_INCLUDE_LOCATION_INFO 的 NetworkCallback 缓存的 SSID——
     * API 31+ 上同步 transportInfo 被系统打码，callback 是唯一非弃用来源；仅在确认
     * 当前 active 连接走 Wi-Fi 后采用。
     */
    fun currentSsid(context: Context, callbackSsid: String? = null): String? {
        if (!hasRequiredPermission(context)) return null
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
            return null
        }
        callbackSsid?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wifiInfo = capabilities.transportInfo as? WifiInfo
            normalizeSsid(wifiInfo?.ssid)?.let { return it }
        }
        @Suppress("DEPRECATION")
        val fallback = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.connectionInfo
            ?.ssid
        return normalizeSsid(fallback)
    }

    fun normalizeSsid(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed == WifiManager.UNKNOWN_SSID || trimmed.equals("<unknown ssid>", ignoreCase = true)) return null
        return if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }.takeIf { it.isNotEmpty() }
    }
}
