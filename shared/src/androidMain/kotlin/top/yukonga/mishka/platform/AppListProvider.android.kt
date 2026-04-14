package top.yukonga.mishka.platform

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual class AppListProvider actual constructor(private val context: PlatformContext) {

    actual suspend fun getInstalledApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val selfPackage = context.packageName

        @Suppress("DEPRECATION")
        val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

        packages
            .filter { pkg ->
                // 排除自身
                pkg.packageName != selfPackage &&
                    // 需有 INTERNET 权限或为系统应用
                    (pkg.requestedPermissions?.contains(Manifest.permission.INTERNET) == true ||
                        pkg.applicationInfo?.let { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 } == true)
            }
            .mapNotNull { pkg ->
                val appInfo = pkg.applicationInfo ?: return@mapNotNull null
                AppInfo(
                    packageName = pkg.packageName,
                    appName = appInfo.loadLabel(pm).toString(),
                    isSystemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
            .sortedBy { it.appName.lowercase() }
    }
}
