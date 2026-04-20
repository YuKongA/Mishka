package top.yukonga.mishka.platform

actual class AppListProvider actual constructor(context: PlatformContext) {

    actual suspend fun getInstalledApps(): List<AppInfo> = emptyList()

    actual suspend fun resolveUids(packageNames: Set<String>): Set<Int> = emptySet()
}
