package top.yukonga.mishka.platform

import android.content.ComponentName
import android.content.pm.PackageManager

class BootStartManager(private val context: PlatformContext) {

    private fun getBootReceiverComponent(): ComponentName {
        return ComponentName(context.packageName, "${context.packageName}.service.BootReceiver")
    }

    fun setEnabled(enabled: Boolean) {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(
            getBootReceiverComponent(),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    fun isEnabled(): Boolean {
        val pm = context.packageManager
        return pm.getComponentEnabledSetting(getBootReceiverComponent()) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }
}
