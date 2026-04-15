package top.yukonga.mishka.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import top.yukonga.mishka.data.api.MihomoApiClient
import top.yukonga.mishka.data.api.MihomoWebSocket
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.util.FormatUtils

/**
 * 动态通知管理器。
 * 通过 WebSocket 接收实时流量数据，更新前台服务通知。
 * 由 MishkaTunService 和 MishkaRootService 共用。
 */
class DynamicNotificationManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private var trafficJob: Job? = null
    private var screenReceiver: BroadcastReceiver? = null

    fun start(secret: String, profileName: String) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val apiClient = MihomoApiClient(secret = secret)
        val webSocket = MihomoWebSocket(apiClient)

        var isScreenOn = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true

        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                isScreenOn = intent?.action == Intent.ACTION_SCREEN_ON
            }
        }
        context.registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )

        trafficJob = scope.launch {
            webSocket.trafficFlow()
                .catch { Log.w(TAG, "Traffic flow error: $it") }
                .collect { traffic ->
                    if (!isScreenOn) return@collect
                    val notification = NotificationHelper.buildDynamicNotification(
                        context = context,
                        profileName = profileName,
                        uploadTotal = FormatUtils.formatBytes(traffic.upTotal),
                        downloadTotal = FormatUtils.formatBytes(traffic.downTotal),
                        uploadSpeed = FormatUtils.formatSpeed(traffic.up),
                        downloadSpeed = FormatUtils.formatSpeed(traffic.down),
                    )
                    notificationManager?.notify(NotificationHelper.NOTIFICATION_ID_VPN, notification)
                }
        }
    }

    /**
     * 根据设置启动动态通知或显示静态通知。
     */
    fun startOrFallbackStatic(storage: PlatformStorage, secret: String) {
        val isDynamic = storage.getString(StorageKeys.DYNAMIC_NOTIFICATION, "true") == "true"
        if (isDynamic) {
            val profileName = storage.getString(StorageKeys.ACTIVE_PROFILE_NAME, "Mishka")
            start(secret, profileName)
        } else {
            val notification = NotificationHelper.buildRunningNotification(context)
            context.getSystemService(NotificationManager::class.java)
                ?.notify(NotificationHelper.NOTIFICATION_ID_VPN, notification)
        }
    }

    fun stop() {
        trafficJob?.cancel()
        trafficJob = null
        screenReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        screenReceiver = null
    }

    companion object {
        private const val TAG = "DynamicNotification"
    }
}
