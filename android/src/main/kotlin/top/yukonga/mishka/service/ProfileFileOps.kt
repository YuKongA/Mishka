package top.yukonga.mishka.service

import android.content.Context
import java.io.File

/**
 * 订阅文件操作：两阶段目录结构（imported/pending/processing）的文件管理。
 * 从 ConfigGenerator 拆分，专注于订阅配置文件的 CRUD 和生命周期。
 */
object ProfileFileOps {

    private fun getWorkDir(context: Context): File {
        val dir = File(context.filesDir, "mihomo")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // === 目录访问 ===

    fun getImportedDir(context: Context, uuid: String): File {
        val dir = File(getWorkDir(context), "imported/$uuid")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getPendingDir(context: Context, uuid: String): File {
        val dir = File(getWorkDir(context), "pending/$uuid")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getProcessingDir(context: Context): File {
        val dir = File(getWorkDir(context), "processing")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getSubscriptionDir(context: Context, uuid: String): File =
        getImportedDir(context, uuid)

    fun getSubscriptionConfigFile(context: Context, uuid: String): File =
        File(getImportedDir(context, uuid), "config.yaml")

    // === 配置保存 ===

    fun saveSubscriptionConfig(context: Context, uuid: String, content: String): File {
        val dir = getImportedDir(context, uuid)
        val file = File(dir, "config.yaml")
        file.writeText(content)
        return file
    }

    fun savePendingConfig(context: Context, uuid: String, content: String): File {
        val dir = getPendingDir(context, uuid)
        val file = File(dir, "config.yaml")
        file.writeText(content)
        return file
    }

    // === 两阶段文件操作 ===

    fun commitPendingToImported(context: Context, uuid: String) {
        val pending = getPendingDir(context, uuid)
        val imported = getImportedDir(context, uuid)
        if (pending.exists()) {
            imported.deleteRecursively()
            pending.copyRecursively(imported, overwrite = true)
            pending.deleteRecursively()
        }
    }

    fun releasePending(context: Context, uuid: String) {
        val pending = File(getWorkDir(context), "pending/$uuid")
        if (pending.exists()) pending.deleteRecursively()
    }

    fun deleteProfileDirs(context: Context, uuid: String) {
        val imported = File(getWorkDir(context), "imported/$uuid")
        val pending = File(getWorkDir(context), "pending/$uuid")
        if (imported.exists()) imported.deleteRecursively()
        if (pending.exists()) pending.deleteRecursively()
    }

    fun cloneImportedToPending(context: Context, sourceUuid: String, targetUuid: String) {
        val source = getImportedDir(context, sourceUuid)
        val target = getPendingDir(context, targetUuid)
        if (source.exists()) {
            source.copyRecursively(target, overwrite = true)
        }
    }

    /** 读取订阅目录的最后修改时间（不创建目录）。目录不存在或 mtime <= 0 返回 null。 */
    fun getProfileDirLastModified(context: Context, uuid: String, pending: Boolean): Long? {
        val sub = if (pending) "pending/$uuid" else "imported/$uuid"
        val dir = File(getWorkDir(context), sub)
        if (!dir.exists()) return null
        return dir.lastModified().takeIf { it > 0 }
    }

    /** 列出 imported/{uuid} 下所有普通文件的相对路径（递归）。目录不存在返回空列表。 */
    fun listImportedFiles(context: Context, uuid: String): List<String> {
        val root = File(getWorkDir(context), "imported/$uuid")
        if (!root.exists() || !root.isDirectory) return emptyList()
        val result = mutableListOf<String>()
        root.walkTopDown().forEach { file ->
            if (file.isFile) {
                val rel = file.relativeTo(root).invariantSeparatorsPath
                result.add(rel)
            }
        }
        return result.sorted()
    }

    fun readImportedFile(context: Context, uuid: String, relativePath: String): String? {
        val root = File(getWorkDir(context), "imported/$uuid")
        val target = File(root, relativePath)
        val canonicalRoot = root.canonicalFile
        val canonicalTarget = runCatching { target.canonicalFile }.getOrNull() ?: return null
        if (!canonicalTarget.startsWith(canonicalRoot)) return null
        if (!canonicalTarget.isFile) return null
        return runCatching { canonicalTarget.readText() }.getOrNull()
    }

    fun writeImportedFile(context: Context, uuid: String, relativePath: String, content: String) {
        val root = File(getWorkDir(context), "imported/$uuid")
        val target = File(root, relativePath)
        val canonicalRoot = root.canonicalFile
        val canonicalTarget = target.canonicalFile
        require(canonicalTarget.startsWith(canonicalRoot)) {
            "Path traversal blocked: $relativePath"
        }
        canonicalTarget.parentFile?.mkdirs()
        canonicalTarget.writeText(content)
    }

    // === GeoIP 共享管理 ===

    private val GEODATA_FILES = listOf(
        "Country.mmdb", "country.mmdb",
        "geoip.dat", "GeoIP.dat",
        "geosite.dat", "GeoSite.dat",
        "ASN.mmdb", "asn.mmdb",
    )

    fun getGeodataDir(context: Context): File {
        val dir = File(getWorkDir(context), "geodata")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 为订阅目录创建 GeoIP 文件的符号链接（失败则复制）。
     * 确保 mihomo -t/-d 在订阅目录下能找到 GeoIP 文件。
     */
    fun ensureGeodataLinks(context: Context, subscriptionDir: File) {
        val geodataDir = getGeodataDir(context)
        for (fileName in GEODATA_FILES) {
            val source = File(geodataDir, fileName)
            val target = File(subscriptionDir, fileName)
            if (source.exists() && !target.exists()) {
                try {
                    java.nio.file.Files.createSymbolicLink(target.toPath(), source.toPath())
                } catch (_: Exception) {
                    try {
                        source.copyTo(target, overwrite = false)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    /**
     * 校验成功后，收集订阅目录中新下载的 GeoIP 文件到共享目录。
     */
    fun collectGeodataFiles(context: Context, subscriptionDir: File) {
        val geodataDir = getGeodataDir(context)
        for (fileName in GEODATA_FILES) {
            val file = File(subscriptionDir, fileName)
            if (file.exists() && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
                try {
                    val target = File(geodataDir, fileName)
                    file.copyTo(target, overwrite = true)
                } catch (_: Exception) {
                }
            }
        }
    }

    // === 迁移 ===

    fun migrateProfileDirs(context: Context) {
        val oldProfilesDir = File(getWorkDir(context), "profiles")
        if (!oldProfilesDir.exists()) return
        val importedBaseDir = File(getWorkDir(context), "imported")
        importedBaseDir.mkdirs()
        oldProfilesDir.listFiles()?.forEach { subDir ->
            if (subDir.isDirectory) {
                val target = File(importedBaseDir, subDir.name)
                if (!target.exists()) {
                    subDir.copyRecursively(target, overwrite = true)
                }
            }
        }
        oldProfilesDir.deleteRecursively()
    }
}
