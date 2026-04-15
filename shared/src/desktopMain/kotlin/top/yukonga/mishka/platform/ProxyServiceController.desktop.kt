package top.yukonga.mishka.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual class ProxyServiceController {

    private val _status = MutableStateFlow(ProxyServiceStatus())
    actual val status: StateFlow<ProxyServiceStatus> = _status.asStateFlow()

    actual fun start(subscriptionId: String?) {
        // Desktop: TODO
    }

    actual fun stop() {
        // Desktop: TODO
    }

    actual fun requestVpnPermission() {
        // Desktop: 不需要 VPN 权限
    }

    actual fun hasVpnPermission(): Boolean = true

    actual fun hasRootPermission(): Boolean = false

    actual fun getTunMode(): TunMode = TunMode.Vpn

    actual fun verifyAndSyncState() {}
}
