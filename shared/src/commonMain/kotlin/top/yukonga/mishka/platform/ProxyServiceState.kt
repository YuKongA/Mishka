package top.yukonga.mishka.platform

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局服务状态桥接。
 * Android 端的 TunService 写入此状态，shared 层的 ViewModel 读取。
 */
object ProxyServiceBridge {
    private val _state = MutableStateFlow(ProxyServiceStatus())
    val state: StateFlow<ProxyServiceStatus> = _state.asStateFlow()

    // 通知刷新事件，设置页切换动态通知时触发
    private val _notificationRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val notificationRefresh: SharedFlow<Unit> = _notificationRefresh.asSharedFlow()

    fun updateState(status: ProxyServiceStatus) {
        _state.value = status
    }

    fun requestNotificationRefresh() {
        _notificationRefresh.tryEmit(Unit)
    }
}
