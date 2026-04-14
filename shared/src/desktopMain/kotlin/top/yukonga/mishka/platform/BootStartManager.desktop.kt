package top.yukonga.mishka.platform

actual class BootStartManager actual constructor(context: PlatformContext) {

    actual fun setEnabled(enabled: Boolean) {
        // Desktop 不支持开机自启
    }

    actual fun isEnabled(): Boolean = false
}
