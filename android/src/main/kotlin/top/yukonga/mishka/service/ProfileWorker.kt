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
import top.yukonga.mishka.R
import top.yukonga.mishka.data.database.getAppDatabase
import top.yukonga.mishka.data.repository.ProfileProcessor
import top.yukonga.mishka.data.repository.SubscriptionFetcher
import top.yukonga.mishka.data.repository.SubscriptionRepository
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.util.describe
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 配置后台更新前台服务。
 *
 * 接收更新请求，在后台执行配置下载、Provider 下载、验证，
 * 显示进度通知和结果通知。
 */
class ProfileWorker : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentLinkedQueue<Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(
                NotificationHelper.NOTIFICATION_ID_PROFILE_WORKER,
                NotificationHelper.buildProfileWorkerNotification(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            return
        }

        // 所有任务完成后自动停止
        scope.launch {
            delay(10_000) // 等待任务提交
            while (true) {
                jobs.poll()?.join() ?: break
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
                    jobs.offer(job)
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

        val storage = PlatformStorage(this)
        val fileManager = AndroidProfileFileManager(this)
        val repo = SubscriptionRepository(
            importedDao = importedDao,
            pendingDao = database.pendingDao(),
            selectionDao = database.selectionDao(),
            storage = storage,
            fileManager = fileManager,
            scope = scope,
        )
        val fetcher = SubscriptionFetcher(userAgent = "ClashMetaForAndroid/${misc.VersionInfo.VERSION_NAME}")
        val processor = ProfileProcessor(repo, fileManager, fetcher)

        try {
            notificationManager.notify(
                statusId,
                NotificationHelper.buildProfileUpdatingNotification(this, imported.name),
            )

            processor.update(uuid)

            NotificationHelper.notifyProfileUpdateSuccess(this, imported.name)
            Log.i(TAG, "Profile ${imported.name} updated successfully")

            importedDao.queryByUUID(uuid)?.let { updated ->
                ProfileReceiver.scheduleNext(this, updated)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update profile ${imported.name}", e)
            NotificationHelper.notifyProfileUpdateFailed(
                this, imported.name, e.describe().ifBlank { getString(R.string.notification_unknown_error) }
            )
        } finally {
            fetcher.close()
            notificationManager.cancel(statusId)
        }
    }

    companion object {
        private const val TAG = "ProfileWorker"
        const val ACTION_SCHEDULE_UPDATES = "top.yukonga.mishka.action.SCHEDULE_UPDATES"
        const val ACTION_UPDATE_PROFILE = "top.yukonga.mishka.action.UPDATE_PROFILE"
    }
}
