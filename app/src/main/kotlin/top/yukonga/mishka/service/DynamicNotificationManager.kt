package top.yukonga.mishka.service

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import top.yukonga.mishka.R
import top.yukonga.mishka.data.api.MihomoConnectionManager
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.TunMode
import top.yukonga.mishka.util.FormatUtils

/**
 * 动态通知管理器。
 * 通过共享的 [MihomoConnectionManager.repository] 拿 traffic 流，更新前台服务通知。
 * 不持有自己的 HttpClient——所有 mihomo 客户端实例由 connectionManager 单点管理。
 */
class DynamicNotificationManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val connectionManager: MihomoConnectionManager,
) {

    private var trafficJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(profileName: String) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        // mihomo 重启时 connectionManager.repository 切到新实例，flatMapLatest 自动取消旧 trafficFlow 收集
        trafficJob = scope.launch {
            connectionManager.repository
                .filterNotNull()
                .flatMapLatest { it.trafficFlow() }
                .collect { traffic ->
                    runCatching {
                        val notification = NotificationHelper.buildDynamicNotification(
                            context = context,
                            profileName = profileName,
                            uploadTotal = FormatUtils.formatBytes(traffic.upTotal),
                            downloadTotal = FormatUtils.formatBytes(traffic.downTotal),
                            uploadSpeed = FormatUtils.formatSpeed(traffic.up),
                            downloadSpeed = FormatUtils.formatSpeed(traffic.down),
                        )
                        notificationManager?.notify(NotificationHelper.NOTIFICATION_ID_VPN, notification)
                    }.onFailure { Log.w(TAG, "Notify failed: $it") }
                }
        }
    }

    /**
     * 根据设置启动动态通知或显示静态通知。
     *
     * ROOT 模式（RootTun / RootTproxy）下强制走静态：app 进程无 VpnService 系统 binding 加持，
     * 后台时 device idle 让 1 Hz `/traffic` WS 帧合并 + `notify()` 批处理，动态通知会冻结
     */
    fun startOrFallbackStatic(storage: PlatformStorage, tunMode: TunMode = TunMode.Vpn) {
        val isDynamicEnabled = storage.getString(StorageKeys.DYNAMIC_NOTIFICATION, "true") == "true"
        val isDynamic = isDynamicEnabled && tunMode == TunMode.Vpn
        if (isDynamic) {
            val profileName = storage.getString(StorageKeys.ACTIVE_PROFILE_NAME, "Mishka")
            start(profileName)
        } else {
            // 与设置页的隧道模式名同源，避免通知里出现「Root TUN」而设置里写「ROOT TUN」
            val mode = context.getString(
                when (tunMode) {
                    TunMode.RootTun -> R.string.settings_tun_mode_root_tun
                    TunMode.RootTproxy -> R.string.settings_tun_mode_root_tproxy
                    TunMode.Vpn -> R.string.settings_tun_mode_vpn
                }
            )
            val notification = NotificationHelper.buildRunningNotification(context, mode)
            context.getSystemService(NotificationManager::class.java)
                ?.notify(NotificationHelper.NOTIFICATION_ID_VPN, notification)
        }
    }

    fun stop() {
        trafficJob?.cancel()
        trafficJob = null
    }

    companion object {
        private const val TAG = "DynamicNotification"
    }
}
