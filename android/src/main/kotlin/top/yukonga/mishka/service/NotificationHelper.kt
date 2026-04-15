package top.yukonga.mishka.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import top.yukonga.mishka.MainActivity
import top.yukonga.mishka.R
import java.util.concurrent.atomic.AtomicInteger

object NotificationHelper {

    // VPN 服务通知
    private const val CHANNEL_VPN = "mishka_vpn"
    const val NOTIFICATION_ID_VPN = 1

    // 配置更新进度通知
    private const val CHANNEL_PROFILE_STATUS = "mishka_profile_status"
    const val NOTIFICATION_ID_PROFILE_WORKER = 2

    // 配置更新结果通知
    private const val CHANNEL_PROFILE_RESULT = "mishka_profile_result"
    private val nextResultId = AtomicInteger(100)

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_VPN,
                    "VPN 服务状态",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Mishka 代理服务运行状态"
                    setShowBadge(false)
                },
                NotificationChannel(
                    CHANNEL_PROFILE_STATUS,
                    "配置更新进度",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "配置下载和验证进度"
                    setShowBadge(false)
                },
                NotificationChannel(
                    CHANNEL_PROFILE_RESULT,
                    "配置更新结果",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "配置更新成功或失败的通知"
                },
            )
        )
    }

    @Deprecated("Use createChannels", ReplaceWith("createChannels(context)"))
    fun createChannel(context: Context) = createChannels(context)

    // === VPN 通知 ===

    fun buildNotification(context: Context, title: String, content: String): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(context, CHANNEL_VPN)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun buildLoadingNotification(context: Context): Notification {
        return buildNotification(context, "Mishka", "正在启动...")
    }

    fun buildRunningNotification(context: Context, mode: String = "VpnService"): Notification {
        return buildNotification(context, "Mishka 运行中", "模式: $mode")
    }

    @Deprecated("Use NOTIFICATION_ID_VPN", ReplaceWith("NOTIFICATION_ID_VPN"))
    const val NOTIFICATION_ID_VALUE = NOTIFICATION_ID_VPN

    // === 配置更新进度通知 ===

    fun buildProfileWorkerNotification(context: Context): Notification {
        return Notification.Builder(context, CHANNEL_PROFILE_STATUS)
            .setContentTitle("配置更新")
            .setContentText("正在运行...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun buildProfileUpdatingNotification(context: Context, name: String): Notification {
        return Notification.Builder(context, CHANNEL_PROFILE_STATUS)
            .setContentTitle("正在更新配置")
            .setContentText(name)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(CHANNEL_PROFILE_STATUS)
            .build()
    }

    // === 配置更新结果通知 ===

    fun notifyProfileUpdateSuccess(context: Context, name: String): Int {
        val id = nextResultId.getAndIncrement()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(context, CHANNEL_PROFILE_RESULT)
            .setContentTitle("更新成功")
            .setContentText("$name 已更新")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setGroup(CHANNEL_PROFILE_RESULT)
            .build()

        context.getSystemService(NotificationManager::class.java).notify(id, notification)
        return id
    }

    fun notifyProfileUpdateFailed(context: Context, name: String, reason: String): Int {
        val id = nextResultId.getAndIncrement()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = "$name: $reason"
        val notification = Notification.Builder(context, CHANNEL_PROFILE_RESULT)
            .setContentTitle("更新失败")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setGroup(CHANNEL_PROFILE_RESULT)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .build()

        context.getSystemService(NotificationManager::class.java).notify(id, notification)
        return id
    }
}
