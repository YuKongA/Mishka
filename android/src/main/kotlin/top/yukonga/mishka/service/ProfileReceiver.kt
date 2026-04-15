package top.yukonga.mishka.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.yukonga.mishka.data.database.ImportedEntity
import top.yukonga.mishka.data.database.getAppDatabase
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 配置自动更新调度器（对齐 CMFA ProfileReceiver）。
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
                CoroutineScope(Dispatchers.IO).launch {
                    reset()
                    startProfileWorker(context, ProfileWorker.ACTION_SCHEDULE_UPDATES)
                }
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
                    .filter { it.type != "File" && it.interval >= MIN_INTERVAL_MS }
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

            val current = System.currentTimeMillis()
            val configFile = File(
                ProfileFileOps.getImportedDir(context, imported.uuid),
                "config.yaml"
            )
            val lastModified = if (configFile.exists()) configFile.lastModified() else -1L
            if (lastModified < 0) return

            val delay = (imported.interval - (current - lastModified)).coerceAtLeast(0)

            Log.i(TAG, "Schedule ${imported.uuid} (${imported.name}) in ${delay / 1000}s")
            alarmManager.set(AlarmManager.RTC, current + delay, intent)
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
            context.startForegroundService(intent)
        }
    }
}
