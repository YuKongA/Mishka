package top.yukonga.mishka.platform

object WifiPolicyIntents {
    const val ACTION_START = "top.yukonga.mishka.action.WIFI_POLICY_START"
    const val ACTION_STOP = "top.yukonga.mishka.action.WIFI_POLICY_STOP"
    const val ACTION_EVALUATE = "top.yukonga.mishka.action.WIFI_POLICY_EVALUATE"
}

enum class WifiPolicyAction(val storageValue: String) {
    StopService("stop_service"),
    DirectMode("direct");

    companion object {
        fun fromStorage(value: String): WifiPolicyAction =
            entries.firstOrNull { it.storageValue == value } ?: StopService
    }
}

expect class WifiPolicyController(context: PlatformContext) {
    fun hasRequiredPermission(): Boolean
    fun currentSsid(): String?
    fun startMonitor()
    fun stopMonitor()
    fun evaluateNow()
}
