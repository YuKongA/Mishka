package top.yukonga.mishka.platform

data class AppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
)

expect class AppListProvider(context: PlatformContext) {
    suspend fun getInstalledApps(): List<AppInfo>
}
