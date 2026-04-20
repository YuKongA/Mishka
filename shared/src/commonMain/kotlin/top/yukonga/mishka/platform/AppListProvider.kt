package top.yukonga.mishka.platform

import androidx.compose.runtime.Immutable

@Immutable
data class AppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
)

expect class AppListProvider(context: PlatformContext) {
    suspend fun getInstalledApps(): List<AppInfo>

    /**
     * 把应用包名解析为 Android UID。未安装包忽略。
     * 供 ROOT TPROXY 模式下 iptables `-m owner --uid-owner` 规则使用。
     */
    suspend fun resolveUids(packageNames: Set<String>): Set<Int>
}
