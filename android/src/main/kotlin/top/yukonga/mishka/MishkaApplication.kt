package top.yukonga.mishka

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass
import top.yukonga.mishka.platform.initToastPlatform
import top.yukonga.mishka.service.NotificationHelper
import top.yukonga.mishka.service.ProfileFileOps
import java.io.File
import java.io.FileOutputStream

class MishkaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        initToastPlatform(this)
        NotificationHelper.createChannels(this)
        extractGeoFiles()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val prefs = getSharedPreferences("mishka_prefs", MODE_PRIVATE)
            val enable = prefs.getString("predictive_back", "false") == "true"
            HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback")
            setEnableOnBackInvokedCallback(applicationInfo, enable)
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
