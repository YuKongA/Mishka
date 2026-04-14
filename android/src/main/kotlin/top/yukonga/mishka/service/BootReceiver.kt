package top.yukonga.mishka.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import top.yukonga.mishka.platform.PlatformStorage

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val storage = PlatformStorage(context)
            val shouldAutoStart = storage.getString("auto_start", "false") == "true"
            val wasRunning = storage.getString("service_was_running", "false") == "true"
            if (shouldAutoStart && wasRunning) {
                MishkaTunService.start(context)
            }
        }
    }
}
