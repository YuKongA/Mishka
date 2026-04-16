package top.yukonga.mishka.platform

import kotlinx.coroutines.flow.StateFlow

enum class ProxyState {
    Stopped,
    Starting,
    Running,
    Stopping,
    Error,
}

enum class TunMode { Vpn, Root }

data class ProxyServiceStatus(
    val state: ProxyState = ProxyState.Stopped,
    val secret: String = "",
    val errorMessage: String = "",
    val tunMode: TunMode = TunMode.Vpn,
    val startTime: Long = 0L,
)

expect class ProxyServiceController {
    val status: StateFlow<ProxyServiceStatus>
    fun start(subscriptionId: String? = null)
    fun stop()
    fun requestVpnPermission()
    fun hasVpnPermission(): Boolean
    fun hasRootPermission(): Boolean
    fun getTunMode(): TunMode
    fun verifyAndSyncState()
}
