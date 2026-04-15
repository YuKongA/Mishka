package top.yukonga.mishka.platform

import kotlinx.coroutines.flow.StateFlow

enum class ProxyState {
    Stopped,
    Starting,
    Running,
    Error,
}

enum class TunMode { Vpn, Root }

data class ProxyServiceStatus(
    val state: ProxyState = ProxyState.Stopped,
    val secret: String = "",
    val errorMessage: String = "",
    val tunMode: TunMode = TunMode.Vpn,
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
