package top.yukonga.mishka.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlin.time.Clock

/**
 * 配置自动更新的广播入口。
 *
 * 系统事件（开机、升级、时间变更）触发全量对账，闹钟到点触发单个配置更新。
 * 闹钟的布置与撤销都在 [ProfileUpdateScheduler]，本类只负责把广播转成前台服务请求。
 */
class ProfileReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "onReceive: ${intent.action}")
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED -> {
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

        private fun startProfileWorker(context: Context, action: String, uuid: String? = null) {
            val intent = Intent(context, ProfileWorker::class.java).apply {
                this.action = action
                if (uuid != null) data = Uri.parse("uuid://$uuid")
            }
            val handoffToken = TransitionWakeLock.holdHandoff(context, intent)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                TransitionWakeLock.releaseHandoff(handoffToken)
                // Android 12+ ForegroundServiceStartNotAllowedException 及各 OEM ROM 后台启动限制；
                // 吞异常避免 BroadcastReceiver 进程崩溃，同时主动 re-arm 保调度链不断
                Log.e(TAG, "startForegroundService failed for $action", e)
                rescheduleAfterFailure(context, action, uuid)
            }
        }

        private fun rescheduleAfterFailure(context: Context, action: String, uuid: String?) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val retryDelay = ProfileUpdateScheduler.MIN_INTERVAL_MS
            val triggerAt = Clock.System.now().toEpochMilliseconds() + retryDelay

            when (action) {
                ProfileWorker.ACTION_UPDATE_PROFILE -> {
                    uuid ?: return
                    val pi = ProfileUpdateScheduler.pendingIntentOf(context, uuid)
                    ProfileUpdateScheduler.scheduleAlarm(alarmManager, triggerAt, pi)
                    Log.i(TAG, "Re-armed $uuid update in ${retryDelay / 1000}s after FGS denial")
                }

                ProfileWorker.ACTION_SCHEDULE_UPDATES -> {
                    // boot / time_change 路径：对账没跑，所有单订阅闹钟链断了；
                    // 用一个 retry PendingIntent 触发下次 onReceive ACTION_BOOT_COMPLETED 分支
                    val retryIntent = Intent(context, ProfileReceiver::class.java).apply {
                        this.action = Intent.ACTION_BOOT_COMPLETED
                    }
                    val pi = PendingIntent.getBroadcast(
                        context, BOOT_RETRY_REQUEST_CODE, retryIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    ProfileUpdateScheduler.scheduleAlarm(alarmManager, triggerAt, pi)
                    Log.i(TAG, "Re-armed reconcile in ${retryDelay / 1000}s after FGS denial")
                }
            }
        }

        private const val BOOT_RETRY_REQUEST_CODE = -1 // 避开 uuid.hashCode() 正值碰撞
    }
}
