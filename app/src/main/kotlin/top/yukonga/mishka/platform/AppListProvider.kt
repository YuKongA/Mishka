package top.yukonga.mishka.platform

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
)

class AppListProvider(private val context: PlatformContext) {

    suspend fun resolveUids(packageNames: Set<String>): Set<Int> = withContext(Dispatchers.IO) {
        if (packageNames.isEmpty()) return@withContext emptySet()
        val pm = context.packageManager
        packageNames.mapNotNullTo(mutableSetOf()) { pkg ->
            try {
                pm.getApplicationInfo(pkg, 0).uid
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    /**
     * 可参与分应用代理的应用：持 INTERNET 权限的，加上系统应用（其网络能力不都经权限声明）。
     *
     * 刻意不用 `getInstalledPackages(GET_PERMISSIONS)`——那会把每个包的完整权限数组搬过
     * Binder，装机量大时直接以 TransactionTooLargeException 崩在分应用代理页。两次窄查询
     * 各自只带包名与 ApplicationInfo，代价远低于一次宽查询。
     */
    suspend fun getInstalledApps(): ImmutableList<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val selfPackage = context.packageName

        @Suppress("DEPRECATION")
        val internetHolders = pm
            .getPackagesHoldingPermissions(arrayOf(Manifest.permission.INTERNET), 0)
            .mapTo(HashSet()) { it.packageName }

        @Suppress("DEPRECATION")
        pm.getInstalledApplications(0)
            .filter { app ->
                app.packageName != selfPackage &&
                        (app.packageName in internetHolders ||
                                app.flags and ApplicationInfo.FLAG_SYSTEM != 0)
            }
            .map { app ->
                AppInfo(
                    packageName = app.packageName,
                    appName = app.loadLabel(pm).toString(),
                    isSystemApp = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
            .sortedBy { it.appName.lowercase() }
            .toPersistentList()
    }
}
