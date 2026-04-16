package top.yukonga.mishka.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import top.yukonga.mishka.data.repository.OverrideStorageHelper
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyServiceStatus
import top.yukonga.mishka.platform.ProxyState
import top.yukonga.mishka.platform.TunMode
import top.yukonga.mishka.R

/**
 * ROOT TUN 模式前台服务。
 * 不经过 VpnService，mihomo 以 root 权限自行创建 TUN 设备和管理路由表。
 */
class MishkaRootService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runner by lazy { MihomoRunner(this) }
    private val dynamicNotification by lazy { DynamicNotificationManager(this, scope) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        startForeground(
            NotificationHelper.NOTIFICATION_ID_VPN,
            NotificationHelper.buildLoadingNotification(this),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val subscriptionId = intent.getStringExtra(EXTRA_SUBSCRIPTION_ID)
                startProxy(subscriptionId)
            }
            ACTION_STOP -> stopProxy()
        }
        return START_STICKY
    }

    private fun startProxy(subscriptionId: String? = null) {
        scope.launch {
            Log.i(TAG, "Starting proxy (ROOT), subscription: $subscriptionId")
            ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Starting, tunMode = TunMode.Root))

            val storage = PlatformStorage(this@MishkaRootService)

            // 1. 尝试重连已有的 mihomo 进程（app 被杀后 root 进程仍存活）
            val existingPid = storage.getString(StorageKeys.ROOT_MIHOMO_PID, "").toIntOrNull() ?: -1
            val existingSecret = storage.getString(StorageKeys.ROOT_MIHOMO_SECRET, "")
            if (existingPid > 0 && existingSecret.isNotEmpty()) {
                if (runner.attachToExisting(existingPid, existingSecret, subscriptionId)) {
                    val existingStartTime = storage.getString(StorageKeys.ROOT_START_TIME, "").toLongOrNull() ?: System.currentTimeMillis()
                    val ec = OverrideStorageHelper.readNullableString(storage, OverrideStorageHelper.KEY_EXTERNAL_CONTROLLER) ?: "127.0.0.1:9090"
                    Log.i(TAG, "Reconnected to existing mihomo: pid=$existingPid")
                    ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Running, secret = existingSecret, externalController = ec, tunMode = TunMode.Root, startTime = existingStartTime))
                    dynamicNotification.startOrFallbackStatic(storage, existingSecret, ec)
                    storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "true")
                    return@launch
                }
                Log.i(TAG, "Existing process pid=$existingPid no longer alive, restarting")
                clearPersistedState(storage)
            }

            // 2. 清理残留进程（上次的进程已失效，确保干净启动）
            RootHelper.cleanupOrphanedMihomo()

            // 3. 检查 ROOT 权限
            if (!RootHelper.hasRootAccess()) {
                Log.e(TAG, "Failed to obtain root access")
                ProxyServiceBridge.updateState(
                    ProxyServiceStatus(ProxyState.Error, errorMessage = getString(R.string.error_root_failed), tunMode = TunMode.Root)
                )
                stopSelf()
                return@launch
            }

            // 4. 生成配置（rootMode = true，不注入 tunFd）
            val result = ConfigGenerator.writeRunConfig(this@MishkaRootService, ConfigGenerator.generateSecret(), subscriptionId, tunFd = -1, rootMode = true)
            runner.secret = result.secret
            runner.externalController = result.externalController

            // 5. 以 root 启动 mihomo
            val success = runner.start(subscriptionId, useRoot = true)
            if (!success) {
                val errorMsg = runner.errorMessage.ifBlank { getString(R.string.error_start_failed) }
                Log.e(TAG, "Failed to start mihomo (ROOT): $errorMsg")
                ProxyServiceBridge.updateState(
                    ProxyServiceStatus(ProxyState.Error, errorMessage = errorMsg, tunMode = TunMode.Root)
                )
                stopSelf()
                return@launch
            }

            // 6. 持久化 PID、secret 和启动时间（用于 app 重启后重连）
            val startTime = System.currentTimeMillis()
            persistState(storage, runner.secret, startTime)

            // 7. 更新状态和通知
            ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Running, secret = runner.secret, externalController = result.externalController, tunMode = TunMode.Root, startTime = startTime))
            dynamicNotification.startOrFallbackStatic(storage, runner.secret, result.externalController)
            storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "true")
            Log.i(TAG, "Proxy running (ROOT)")
        }
    }

    private fun persistState(storage: PlatformStorage, secret: String, startTime: Long) {
        storage.putString(StorageKeys.ROOT_MIHOMO_PID, runner.pid.toString())
        storage.putString(StorageKeys.ROOT_MIHOMO_SECRET, secret)
        storage.putString(StorageKeys.ROOT_START_TIME, startTime.toString())
    }

    private fun clearPersistedState(storage: PlatformStorage) {
        storage.putString(StorageKeys.ROOT_MIHOMO_PID, "")
        storage.putString(StorageKeys.ROOT_MIHOMO_SECRET, "")
        storage.putString(StorageKeys.ROOT_START_TIME, "")
    }

    private fun stopProxy() {
        Log.i(TAG, "Stopping proxy (ROOT)...")
        ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopping, tunMode = TunMode.Root))
        dynamicNotification.stop()
        scope.launch(Dispatchers.IO) {
            runner.stop()
            ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopped))
            val storage = PlatformStorage(this@MishkaRootService)
            clearPersistedState(storage)
            storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        dynamicNotification.stop()
        // 注意：onDestroy 不 kill mihomo，让它继续运行以便重连
        ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopped))
        scope.cancel()
        Log.i(TAG, "MishkaRootService destroyed")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MishkaRootService"
        const val ACTION_START = "top.yukonga.mishka.ROOT_START"
        const val ACTION_STOP = "top.yukonga.mishka.ROOT_STOP"
        const val EXTRA_SUBSCRIPTION_ID = "subscription_id"

        fun start(context: Context, subscriptionId: String? = null) {
            val intent = Intent(context, MishkaRootService::class.java).apply {
                action = ACTION_START
                subscriptionId?.let { putExtra(EXTRA_SUBSCRIPTION_ID, it) }
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MishkaRootService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
