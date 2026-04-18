package top.yukonga.mishka.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.yukonga.mishka.data.database.ImportedEntity
import top.yukonga.mishka.data.database.getAppDatabase
import top.yukonga.mishka.data.model.ProfileType
import java.io.File
import kotlin.time.Clock

/**
 * 配置自动更新调度器。
 *
 * 监听系统事件（开机、升级、时间变更）后重新调度所有配置更新。
 * 监听自定义 ACTION_PROFILE_REQUEST_UPDATE 触发单个配置更新。
 */
class ProfileReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "onReceive: ${intent.action}")
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED -> {
                // onReceive 栈内同步完成，保留 BroadcastReceiver 的 FGS 启动豁免窗口
                reset()
                startProfileWorker(context, ProfileWorker.ACTION_SCHEDULE_UPDATES)
            }
            ACTION_PROFILE_REQUEST_UPDATE -> {
                val uuid = intent.data?.host ?: return
                Log.i(TAG, "Update requested for: $uuid")
                startProfileWorker(context, ProfileWorker.ACTION_UPDATE_PROFILE, uuid)
            }
        }
    }

    companion object {
        private const val TAG = "ProfileReceiver"
        const val ACTION_PROFILE_REQUEST_UPDATE = "top.yukonga.mishka.action.PROFILE_REQUEST_UPDATE"
        private const val MIN_INTERVAL_MS = 15 * 60 * 1000L // 15 分钟

        private val lock = Mutex()
        private var initialized = false

        private fun reset() {
            initialized = false
        }

        /**
         * 为所有非 File 类型的已导入配置重新调度更新。
         */
        suspend fun rescheduleAll(context: Context) = lock.withLock {
            if (initialized) return@withLock

            try {
                Log.i(TAG, "Reschedule all profiles update")
                val database = getAppDatabase(context)
                val importedDao = database.importedDao()

                importedDao.queryAllUUIDs()
                    .mapNotNull { importedDao.queryByUUID(it) }
                    .filter { it.type != ProfileType.File && it.interval >= MIN_INTERVAL_MS }
                    .forEach { scheduleNext(context, it) }

                initialized = true
            } catch (e: Exception) {
                Log.e(TAG, "rescheduleAll failed", e)
            }
        }

        /**
         * 为指定配置调度下次更新。
         */
        fun scheduleNext(context: Context, imported: ImportedEntity) {
            val intent = pendingIntentOf(context, imported.uuid)
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return

            // 先取消旧闹钟
            alarmManager.cancel(intent)

            if (imported.interval < MIN_INTERVAL_MS) return

            val current = Clock.System.now().toEpochMilliseconds()
            val configFile = File(
                ProfileFileOps.getImportedDir(context, imported.uuid),
                "config.yaml"
            )
            val lastModified = if (configFile.exists()) configFile.lastModified() else -1L
            if (lastModified < 0) return

            val delay = (imported.interval - (current - lastModified)).coerceAtLeast(0)
            val triggerAt = current + delay

            Log.i(TAG, "Schedule ${imported.uuid} (${imported.name}) in ${delay / 1000}s")
            scheduleAlarm(alarmManager, triggerAt, intent)
        }

        /**
         * API 31+ 精确闹钟进入 FGS 启动豁免白名单；
         * 若用户在系统"闹钟和提醒"撤销 SCHEDULE_EXACT_ALARM，退化为 inexact，
         * 并依赖 startProfileWorker 的 catch + re-arm 兜底。
         */
        private fun scheduleAlarm(alarmManager: AlarmManager, triggerAt: Long, pi: PendingIntent) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }

        /**
         * 取消指定配置的自动更新。
         */
        fun cancelNext(context: Context, uuid: String) {
            val intent = pendingIntentOf(context, uuid)
            context.getSystemService(AlarmManager::class.java)?.cancel(intent)
        }

        private fun pendingIntentOf(context: Context, uuid: String): PendingIntent {
            val intent = Intent(ACTION_PROFILE_REQUEST_UPDATE).apply {
                setPackage(context.packageName)
                data = Uri.parse("uuid://$uuid")
            }
            return PendingIntent.getBroadcast(
                context,
                uuid.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun startProfileWorker(context: Context, action: String, uuid: String? = null) {
            val intent = Intent(context, ProfileWorker::class.java).apply {
                this.action = action
                if (uuid != null) data = Uri.parse("uuid://$uuid")
            }
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // Android 12+ ForegroundServiceStartNotAllowedException 及各 OEM ROM 后台启动限制；
                // 吞异常避免 BroadcastReceiver 进程崩溃，同时主动 re-arm 保调度链不断
                Log.e(TAG, "startForegroundService failed for $action", e)
                rescheduleAfterFailure(context, action, uuid)
            }
        }

        private fun rescheduleAfterFailure(context: Context, action: String, uuid: String?) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val triggerAt = Clock.System.now().toEpochMilliseconds() + MIN_INTERVAL_MS

            when (action) {
                ProfileWorker.ACTION_UPDATE_PROFILE -> {
                    uuid ?: return
                    val pi = pendingIntentOf(context, uuid)
                    scheduleAlarm(alarmManager, triggerAt, pi)
                    Log.i(TAG, "Re-armed $uuid update in ${MIN_INTERVAL_MS / 1000}s after FGS denial")
                }
                ProfileWorker.ACTION_SCHEDULE_UPDATES -> {
                    // boot / time_change 路径：rescheduleAll 没跑，所有单订阅闹钟链断了；
                    // 用一个 retry PendingIntent 触发下次 onReceive ACTION_BOOT_COMPLETED 分支
                    val retryIntent = Intent(context, ProfileReceiver::class.java).apply {
                        this.action = Intent.ACTION_BOOT_COMPLETED
                    }
                    val pi = PendingIntent.getBroadcast(
                        context, BOOT_RETRY_REQUEST_CODE, retryIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    scheduleAlarm(alarmManager, triggerAt, pi)
                    Log.i(TAG, "Re-armed rescheduleAll in ${MIN_INTERVAL_MS / 1000}s after FGS denial")
                }
            }
        }

        private const val BOOT_RETRY_REQUEST_CODE = -1 // 避开 uuid.hashCode() 正值碰撞
    }
}
