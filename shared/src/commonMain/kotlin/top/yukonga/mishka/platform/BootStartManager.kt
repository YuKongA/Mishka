package top.yukonga.mishka.platform

expect class BootStartManager(context: PlatformContext) {
    fun setEnabled(enabled: Boolean)
    fun isEnabled(): Boolean
}
