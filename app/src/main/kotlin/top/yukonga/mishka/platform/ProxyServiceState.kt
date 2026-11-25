package top.yukonga.mishka.platform

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 启动/重启期间的细粒度阶段，用于主页状态卡片展示实时进度。
 * 仅携带结构化阶段与对象名，本地化文案由 UI 层按 locale 映射。
 */
enum class StartupStep {
    CleaningUp,
    Reconnecting,
    Preparing,
    EstablishingVpn,
    StartingCore,
    Transforming,
    ParsingConfig,
    DownloadingGeo,
    LoadingGeo,
    StartingListeners,
    LoadingProxyProviders,
    LoadingRuleProviders,
    DownloadingUi,
    StartingDns,
    ApplyingRules,
}

@Immutable
data class StartupProgress(
    val step: StartupStep,
    val detail: String = "",
    val current: Int = 0,
    val total: Int = 0,
)

/**
 * 全局服务状态桥接。
 * Android 端的 TunService / RootService 写入此状态，ViewModel 读取。
 */
object ProxyServiceBridge {
    private val stateLock = Any()

    private val _state = MutableStateFlow(ProxyServiceStatus())
    val state: StateFlow<ProxyServiceStatus> = _state.asStateFlow()

    // 独立于 state：避免启动进度刷新触发 Tile / MihomoConnectionManager 等仅关心生命周期状态的订阅方
    private val _startupProgress = MutableStateFlow<StartupProgress?>(null)
    val startupProgress: StateFlow<StartupProgress?> = _startupProgress.asStateFlow()

    // 通知刷新事件，设置页切换动态通知时触发
    private val _notificationRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val notificationRefresh: SharedFlow<Unit> = _notificationRefresh.asSharedFlow()

    fun updateState(status: ProxyServiceStatus, startupProgress: StartupProgress? = null) {
        synchronized(stateLock) {
            _state.value = status
            _startupProgress.value = if (status.state == ProxyState.Starting) startupProgress else null
        }
    }

    fun updateStartupProgress(progress: StartupProgress?) {
        synchronized(stateLock) {
            if (_state.value.state == ProxyState.Starting) {
                _startupProgress.value = progress
            } else if (progress == null) {
                _startupProgress.value = null
            }
        }
    }

    /**
     * 置停止态。**tunMode 必须带上**：消费方在 Stopped 时只能回读 storage，而 storage 是
     * 「用户当前选择」，不是「刚才在跑的那个」，两者在用户改了模式还没重启时并不相等。
     */
    fun markStopped(tunMode: TunMode) {
        synchronized(stateLock) {
            _startupProgress.value = null
            _state.value = ProxyServiceStatus(ProxyState.Stopped, tunMode = tunMode)
        }
    }

    /**
     * onDestroy 专用。失败路径是 `updateState(Error)` + `stopSelf()`，紧接着就走到 onDestroy，
     * 无条件写 Stopped 会抹掉刚写入的 Error 与 errorMessage，用户只看到「启动中 → 未运行」，
     * 失败原因只剩 logcat。Error 是终态，只有非 Error 时才落 Stopped。
     */
    fun markStoppedUnlessError(tunMode: TunMode) {
        synchronized(stateLock) {
            _startupProgress.value = null
            _state.update { current ->
                if (current.state == ProxyState.Error) current
                else ProxyServiceStatus(ProxyState.Stopped, tunMode = tunMode)
            }
        }
    }

    fun requestNotificationRefresh() {
        _notificationRefresh.tryEmit(Unit)
    }
}
