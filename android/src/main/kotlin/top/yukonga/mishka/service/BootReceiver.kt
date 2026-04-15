package top.yukonga.mishka.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import top.yukonga.mishka.platform.PlatformStorage

/**
 * 自动重启接收器（对齐 CMFA RestartReceiver）。
 *
 * 监听开机和应用升级事件，如果上次代理正在运行则自动重启。
 * 开关通过 BootStartManager 控制本 Receiver 的 enabled/disabled 状态，
 * 判断条件仅看 service_was_running（由 TunService 启停时写入）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val storage = PlatformStorage(context)
                val wasRunning = storage.getString("service_was_running", "false") == "true"
                if (wasRunning) {
                    val subscriptionId = storage.getString("active_profile_uuid", "")
                        .ifEmpty { null }
                    MishkaTunService.start(context, subscriptionId)
                }
            }
        }
    }
}
