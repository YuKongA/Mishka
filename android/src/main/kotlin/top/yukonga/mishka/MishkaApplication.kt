package top.yukonga.mishka

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.util.Log
import kotlin.concurrent.thread
import org.lsposed.hiddenapibypass.HiddenApiBypass
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.initToastPlatform
import top.yukonga.mishka.service.NotificationHelper
import top.yukonga.mishka.service.ProfileFileOps
import top.yukonga.mishka.service.RootHelper
import java.io.File
import java.io.FileOutputStream

class MishkaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        initToastPlatform(this)
        NotificationHelper.createChannels(this)
        extractGeoFiles()
        reclaimRootOwnedImported()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val prefs = getSharedPreferences("mishka_prefs", MODE_PRIVATE)
            val enable = prefs.getString("predictive_back", "false") == "true"
            HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback")
            setEnableOnBackInvokedCallback(applicationInfo, enable)
        }
    }

    /**
     * 一次性迁移：旧版本 mihomo 以 root 身份直接在 imported/{uuid}/ 下写 provider/ruleset 缓存，
     * 导致更新/删除时 app 无权限 unlink。新版改走独立 runtime/{uuid}/ 沙箱，但旧遗孤仍需 chown 回收。
     * 需要 ROOT 权限；无 root 的设备跳过（此前就不会产生 root 遗孤）。
     */
    private fun reclaimRootOwnedImported() {
        val storage = PlatformStorage(this)
        if (storage.getString(StorageKeys.MIGRATION_ROOT_RECLAIM_DONE, "false") == "true") return
        thread(name = "root-reclaim", isDaemon = true) {
            val imported = File(filesDir, "mihomo/imported")
            if (!imported.exists()) {
                storage.putString(StorageKeys.MIGRATION_ROOT_RECLAIM_DONE, "true")
                return@thread
            }
            if (RootHelper.hasRootAccess()) {
                val uid = Process.myUid()
                val ok = RootHelper.chownRecursiveAsRoot(imported.absolutePath, uid)
                Log.i(TAG, "One-shot chown imported/ to uid=$uid: ok=$ok")
                if (ok) storage.putString(StorageKeys.MIGRATION_ROOT_RECLAIM_DONE, "true")
            } else {
                // 无 ROOT → 不可能有旧 root 遗孤，直接打标记跳过
                storage.putString(StorageKeys.MIGRATION_ROOT_RECLAIM_DONE, "true")
            }
        }
    }

    /**
     * 从 assets 提取预制 GeoIP 文件到 geodata/ 共享目录。
     * 应用更新后自动替换旧文件，文件已存在且未过期则跳过。
     */
    private fun extractGeoFiles() {
        val geodataDir = ProfileFileOps.getGeodataDir(this)
        val updateDate = packageManager.getPackageInfo(packageName, 0).lastUpdateTime

        val geoFiles = listOf("geoip.metadb", "geosite.dat", "ASN.mmdb")
        for (fileName in geoFiles) {
            val target = File(geodataDir, fileName)
            if (target.exists() && target.lastModified() < updateDate) {
                target.delete()
            }
            if (!target.exists()) {
                runCatching {
                    FileOutputStream(target).use { assets.open(fileName).copyTo(it) }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MishkaApplication"

        lateinit var instance: MishkaApplication
            private set

        fun setEnableOnBackInvokedCallback(appInfo: ApplicationInfo, enable: Boolean) {
            runCatching {
                val method = ApplicationInfo::class.java.getDeclaredMethod(
                    "setEnableOnBackInvokedCallback",
                    Boolean::class.javaPrimitiveType,
                )
                method.isAccessible = true
                method.invoke(appInfo, enable)
            }
        }
    }
}
