package top.yukonga.mishka.platform

import kotlinx.coroutines.flow.StateFlow

enum class ProxyState {
    Stopped,
    Starting,
    Running,
    Error,
}

data class ProxyServiceStatus(
    val state: ProxyState = ProxyState.Stopped,
    val secret: String = "",
    val errorMessage: String = "",
)

expect class ProxyServiceController {
    val status: StateFlow<ProxyServiceStatus>
    fun start(subscriptionId: String? = null)
    fun stop()
    fun requestVpnPermission()
    fun hasVpnPermission(): Boolean
}
