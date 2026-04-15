package top.yukonga.mishka.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.mishka.data.database.getAppDatabase
import top.yukonga.mishka.data.repository.ConfigProcessor
import top.yukonga.mishka.data.repository.SubscriptionFetcher
import top.yukonga.mishka.data.model.Subscription

/**
 * 配置后台更新前台服务（对齐 CMFA ProfileWorker）。
 *
 * 接收更新请求，在后台执行配置下载、Provider 下载、验证，
 * 显示进度通知和结果通知。
 */
class ProfileWorker : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableListOf<Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(
            NotificationHelper.NOTIFICATION_ID_PROFILE_WORKER,
            NotificationHelper.buildProfileWorkerNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        // 所有任务完成后自动停止
        scope.launch {
            delay(10_000) // 等待任务提交
            while (jobs.any { it.isActive }) {
                delay(1_000)
            }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SCHEDULE_UPDATES -> {
                scope.launch { ProfileReceiver.rescheduleAll(this@ProfileWorker) }
            }
            ACTION_UPDATE_PROFILE -> {
                val uuid = intent.data?.host
                if (uuid != null) {
                    val job = scope.launch { runUpdate(uuid) }
                    jobs.add(job)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runUpdate(uuid: String) {
        val database = getAppDatabase(this)
        val importedDao = database.importedDao()
        val imported = importedDao.queryByUUID(uuid) ?: return

        val notificationManager = getSystemService(NotificationManager::class.java)
        val statusId = NotificationHelper.NOTIFICATION_ID_PROFILE_WORKER + uuid.hashCode().and(0xFFFF)

        try {
            // 显示更新中通知
            notificationManager.notify(
                statusId,
                NotificationHelper.buildProfileUpdatingNotification(this, imported.name),
            )

            val fetcher = SubscriptionFetcher()
            try {
                // 1. 下载配置
                val sub = Subscription(
                    id = imported.uuid,
                    name = imported.name,
                    url = imported.source,
                    upload = imported.upload,
                    download = imported.download,
                    total = imported.total,
                    expire = imported.expire,
                )
                val result = fetcher.fetch(sub)

                // 2. 保存配置
                ConfigGenerator.saveSubscriptionConfig(this, uuid, result.configContent)

                // 3. 下载 Provider
                val providers = ConfigProcessor.parseProviders(result.configContent)
                val downloadable = providers.filter { it.url.isNotEmpty() }
                if (downloadable.isNotEmpty()) {
                    ConfigProcessor.downloadProviders(
                        providers = providers,
                        subscriptionDir = ConfigGenerator.getSubscriptionDir(this, uuid).absolutePath,
                        downloader = { url -> fetcher.downloadBytes(url) },
                    )
                }

                // 4. mihomo -t 校验
                val error = MihomoValidator.validate(
                    this,
                    ConfigGenerator.getSubscriptionDir(this, uuid).absolutePath,
                )
                if (error != null) {
                    throw Exception(error)
                }

                // 5. 更新数据库
                importedDao.update(
                    imported.copy(
                        name = result.subscription.name,
                        upload = result.subscription.upload,
                        download = result.subscription.download,
                        total = result.subscription.total,
                        expire = result.subscription.expire,
                    )
                )

                // 成功通知
                NotificationHelper.notifyProfileUpdateSuccess(this, imported.name)
                Log.i(TAG, "Profile ${imported.name} updated successfully")

                // 重新调度下次更新
                importedDao.queryByUUID(uuid)?.let { updated ->
                    ProfileReceiver.scheduleNext(this, updated)
                }
            } finally {
                fetcher.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update profile ${imported.name}", e)
            NotificationHelper.notifyProfileUpdateFailed(
                this, imported.name, e.message ?: "未知错误"
            )
        } finally {
            notificationManager.cancel(statusId)
        }
    }

    companion object {
        private const val TAG = "ProfileWorker"
        const val ACTION_SCHEDULE_UPDATES = "top.yukonga.mishka.action.SCHEDULE_UPDATES"
        const val ACTION_UPDATE_PROFILE = "top.yukonga.mishka.action.UPDATE_PROFILE"
    }
}
