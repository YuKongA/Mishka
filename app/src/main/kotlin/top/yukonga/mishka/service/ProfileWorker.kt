package top.yukonga.mishka.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import top.yukonga.mishka.R
import top.yukonga.mishka.data.database.getAppDatabase
import top.yukonga.mishka.data.repository.OverrideJsonStore
import top.yukonga.mishka.data.repository.ProfileProcessor
import top.yukonga.mishka.data.repository.SubscriptionProxyResolver
import top.yukonga.mishka.data.repository.SubscriptionRepositoryImpl
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.util.describe
import top.yukonga.mishka.service.TransitionWakeLock.releaseQuietly

/**
 * 配置后台更新前台服务。
 *
 * 接收更新请求，在后台执行配置下载、Provider 下载、验证，
 * 显示进度通知和结果通知。
 */
class ProfileWorker : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private var inFlight = 0
    private var latestStartId = 0
    private val updatedUuids = LinkedHashSet<String>()
    // onCreate 起前台失败后已 stopSelf，之后到达的 start 不能再接活
    @Volatile
    private var foregroundFailed = false

    // 与 UI 侧共享同一 store：内存值是权威值，自建实例读不到刚落的设置
    private val overrideStore: OverrideJsonStore by inject()

    private val updateScheduler: ProfileUpdateScheduler by inject()

    private val serviceController: ProxyServiceController by inject()

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
            foregroundFailed = true
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (foregroundFailed) {
            TransitionWakeLock.releaseHandoff(intent)
            return START_NOT_STICKY
        }
        try {
            val task: (suspend () -> Unit)? = when (intent?.action) {
                ACTION_SCHEDULE_UPDATES -> ({ updateScheduler.reconcileNow() })

                ACTION_UPDATE_PROFILE -> intent.data?.host?.let { uuid -> { runUpdate(uuid) } }

                else -> null
            }
            if (task != null) {
                launchTracked(startId, task)
            } else {
                val stopId = synchronized(stateLock) {
                    latestStartId = startId
                    if (inFlight == 0) latestStartId else null
                }
                stopId?.let(::stopSelfResult)
            }
        } finally {
            TransitionWakeLock.releaseHandoff(intent)
        }
        return START_NOT_STICKY
    }

    /**
     * 跑一件任务，并在整批并发任务归零时以最新 `startId` 请求停止。
     *
     * `latestStartId`、`inFlight` 与 `updatedUuids` 同受 `stateLock` 保护：既避免乱序完成时
     * 较早任务的旧 `startId` 让 `stopSelfResult` 返回 false 滞留前台服务，又保证新到达的
     * `onStartCommand` 不会被前一批的收尾提前停掉。
     */
    private fun launchTracked(startId: Int, block: suspend () -> Unit) {
        synchronized(stateLock) {
            latestStartId = startId
            inFlight++
        }
        val wakeLock = TransitionWakeLock.acquire(
            this,
            WAKELOCK_TAG,
            TransitionWakeLock.PROFILE_UPDATE_TIMEOUT_MS,
        )
        val job = try {
            scope.launch {
                try {
                    block()
                } finally {
                    val (drained, stopId) = synchronized(stateLock) {
                        inFlight--
                        if (inFlight == 0) {
                            val ids = updatedUuids.toList()
                            updatedUuids.clear()
                            ids to latestStartId
                        } else {
                            emptyList<String>() to null
                        }
                    }
                    // 同批多个定时更新全部完成后再触发一次重启，避免切掉后续订阅的 mixed-port
                    // 代理连接，也避免 ROOT 重启的 RootRuntimeCache.release 卡在下一条订阅的 processLock 上。
                    drained.forEach { id ->
                        runCatching { serviceController.restartAfterProfileUpdate(id) }
                            .onFailure { Log.e(TAG, "Failed to trigger restart after updating $id", it) }
                    }
                    stopId?.let(::stopSelfResult)
                }
            }
        } catch (t: Throwable) {
            val stopId = synchronized(stateLock) {
                inFlight--
                if (inFlight == 0) latestStartId else null
            }
            wakeLock.releaseQuietly()
            stopId?.let(::stopSelfResult)
            throw t
        }
        job.invokeOnCompletion { wakeLock.releaseQuietly() }
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
        val statusId = NotificationHelper.profileProgressId(uuid)

        val storage = PlatformStorage(this)
        val fileManager = AndroidProfileFileManager(this)
        val repo = SubscriptionRepositoryImpl(
            importedDao = importedDao,
            pendingDao = database.pendingDao(),
            selectionDao = database.selectionDao(),
            storage = storage,
            fileManager = fileManager,
            scope = scope,
            database = database,
        )
        val proxyResolver = SubscriptionProxyResolver(storage, overrideStore)
        val processor = ProfileProcessor(
            repo = repo,
            fileManager = fileManager,
            defaultProfileName = getString(R.string.subscription_default_name),
            proxyResolver = proxyResolver,
        )

        try {
            notificationManager.notify(
                statusId,
                NotificationHelper.buildProfileUpdatingNotification(this, imported.name),
            )

            processor.update(uuid)

            synchronized(stateLock) { updatedUuids.add(uuid) }

            NotificationHelper.notifyProfileUpdateSuccess(this, imported.name)
            Log.i(TAG, "Profile ${imported.name} updated successfully")
            // 下次闹钟不在这里布置：update 写回 imported 表会让 ProfileUpdateScheduler 的
            // Flow 重新对账，按刚刷新的 config.yaml mtime 续期
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update profile ${imported.name}", e)
            NotificationHelper.notifyProfileUpdateFailed(
                this, imported.name, e.describe().ifBlank { getString(R.string.notification_unknown_error) }
            )
        } finally {
            notificationManager.cancel(statusId)
        }
    }

    companion object {
        private const val WAKELOCK_TAG = "mishka:profile-worker"
        private const val TAG = "ProfileWorker"
        const val ACTION_SCHEDULE_UPDATES = "top.yukonga.mishka.action.SCHEDULE_UPDATES"
        const val ACTION_UPDATE_PROFILE = "top.yukonga.mishka.action.UPDATE_PROFILE"
    }
}
