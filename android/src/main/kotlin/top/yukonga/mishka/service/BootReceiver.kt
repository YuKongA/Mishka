package top.yukonga.mishka.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys

/**
 * 开机/升级自动重启接收器。
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
                val wasRunning = storage.getString(StorageKeys.SERVICE_WAS_RUNNING, "false") == "true"
                if (wasRunning) {
                    val subscriptionId = storage.getString(StorageKeys.ACTIVE_PROFILE_UUID, "")
                        .ifEmpty { null }
                    val mode = storage.getString(StorageKeys.TUN_MODE, "vpn")
                    // legacy "root" 兼容：视为 root_tun
                    val isRoot = mode == "root_tun" || mode == "root_tproxy" || mode == "root"
                    if (isRoot) {
                        MishkaRootService.start(context, subscriptionId)
                    } else {
                        MishkaTunService.start(context, subscriptionId)
                    }
                }
            }
        }
    }
}
