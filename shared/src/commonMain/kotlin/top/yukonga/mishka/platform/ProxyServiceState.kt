package top.yukonga.mishka.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局服务状态桥接。
 * Android 端的 TunService 写入此状态，shared 层的 ViewModel 读取。
 */
object ProxyServiceBridge {
    private val _state = MutableStateFlow(ProxyServiceStatus())
    val state: StateFlow<ProxyServiceStatus> = _state.asStateFlow()

    fun updateState(status: ProxyServiceStatus) {
        _state.value = status
    }
}
