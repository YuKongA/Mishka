package top.yukonga.mishka.platform

import kotlinx.coroutines.flow.StateFlow

enum class ProxyState {
    Stopped,
    Starting,
    Running,
    Stopping,
    Error,
}

enum class TunMode { Vpn, RootTun, RootTproxy }

data class ProxyServiceStatus(
    val state: ProxyState = ProxyState.Stopped,
    val secret: String = "",
    val externalController: String = "127.0.0.1:9090",
    val errorMessage: String = "",
    val tunMode: TunMode = TunMode.Vpn,
    val startTime: Long = 0L,
    val mihomoPid: Int = -1,
)

expect class ProxyServiceController {
    val status: StateFlow<ProxyServiceStatus>
    fun start(subscriptionId: String? = null)
    fun stop()
    fun restart(subscriptionId: String? = null)
    fun requestVpnPermission()
    fun hasVpnPermission(): Boolean
    fun hasRootPermission(): Boolean
    fun getTunMode(): TunMode
    fun verifyAndSyncState()
}
