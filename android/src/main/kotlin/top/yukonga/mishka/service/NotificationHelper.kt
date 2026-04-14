package top.yukonga.mishka.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import top.yukonga.mishka.MainActivity
import top.yukonga.mishka.R

object NotificationHelper {

    private const val CHANNEL_ID = "mishka_vpn"
    private const val NOTIFICATION_ID = 1

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Mishka VPN",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Mishka 代理服务运行状态"
            setShowBadge(false)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    fun buildNotification(context: Context, title: String, content: String): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(context, CHANNEL_ID)
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

    const val NOTIFICATION_ID_VALUE = NOTIFICATION_ID
}
