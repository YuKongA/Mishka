package top.yukonga.mishka.service

import android.content.Context
import java.io.File

/**
 * 订阅文件操作。三阶段目录：pending → processing → imported。
 * processing 是单例目录，串行使用。
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

    /** 单例 processing 沙箱目录。 */
    fun getProcessingDir(context: Context): File {
        val dir = File(getWorkDir(context), "processing")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getSubscriptionDir(context: Context, uuid: String): File = getImportedDir(context, uuid)

    fun getSubscriptionConfigFile(context: Context, uuid: String): File =
        File(getImportedDir(context, uuid), "config.yaml")

    /**
     * ROOT 模式运行时沙箱目录（mihomo 以 uid=0 在此写 provider/ruleset 缓存，不污染 imported/）。
     */
    fun getRuntimeDir(context: Context, uuid: String): File =
        File(getWorkDir(context), "runtime/$uuid")

    // === pending 写入 ===

    fun savePendingConfig(context: Context, uuid: String, content: String): File {
        val dir = getPendingDir(context, uuid)
        val file = File(dir, "config.yaml")
        file.writeText(content)
        return file
    }

    fun releasePending(context: Context, uuid: String) {
        val pending = File(getWorkDir(context), "pending/$uuid")
        if (pending.exists()) pending.deleteRecursively()
    }

    // === processing 沙箱 ===

    /**
     * 清空 processing/ 并复制 pending/{uuid}/ → processing/。
     * 若 pending/{uuid}/ 不存在（File 类型尚未写入），仍创建空 processing/。
     */
    fun prepareProcessing(context: Context, uuid: String): File {
        val processing = getProcessingDir(context)
        processing.deleteRecursively()
        processing.mkdirs()
        val pending = File(getWorkDir(context), "pending/$uuid")
        if (pending.exists()) {
            pending.copyRecursively(processing, overwrite = true)
        }
        return processing
    }

    fun writeProcessingConfig(workDir: String, content: String): File {
        val file = File(workDir, "config.yaml")
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    fun cleanupProcessing(context: Context) {
        val processing = File(getWorkDir(context), "processing")
        if (processing.exists()) processing.deleteRecursively()
    }

    /**
     * 提交：清 imported/{uuid}/ → 复制 processing/ → imported/{uuid}/ → 删 pending/{uuid}/。
     * 用 copy 而非 move：processing 残留由下次 prepareProcessing 清理。
     */
    fun commitProcessingToImported(context: Context, uuid: String) {
        val processing = getProcessingDir(context)
        val imported = File(getWorkDir(context), "imported/$uuid")
        val pending = File(getWorkDir(context), "pending/$uuid")
        // 旧版本 ROOT 模式可能在 imported/ 里留下 root:root 文件，Kotlin delete 失败后走 su 兜底
        if (imported.exists() && !imported.deleteRecursively()) {
            RootHelper.rmRfAsRoot(imported.absolutePath)
        }
        imported.mkdirs()
        if (processing.exists()) {
            processing.copyRecursively(imported, overwrite = true)
        }
        if (pending.exists()) pending.deleteRecursively()
    }

    // === 删除与复制 ===

    fun deleteProfileDirs(context: Context, uuid: String) {
        val imported = File(getWorkDir(context), "imported/$uuid")
        val pending = File(getWorkDir(context), "pending/$uuid")
        val runtime = File(getWorkDir(context), "runtime/$uuid")
        if (imported.exists() && !imported.deleteRecursively()) {
            RootHelper.rmRfAsRoot(imported.absolutePath)
        }
        if (pending.exists()) pending.deleteRecursively()
        // runtime/{uuid} 通常在 ROOT 停止时已被 cleanupRootRuntime 清掉；
        // 此处是兜底：若 app 崩溃未走正常 stop 路径，会有 root:root 残留，Kotlin 删不掉。
        if (runtime.exists() && !runtime.deleteRecursively()) {
            RootHelper.rmRfAsRoot(runtime.absolutePath)
        }
    }

    // === ROOT 运行时沙箱 ===

    /**
     * ROOT 启动前准备：清残留 → 从 imported/{uuid}/ 复制一份到 runtime/{uuid}/（app UID 写入）→ 重建 geodata 链接。
     * imported/ 里已包含 -prefetch 落盘的 provider 文件，copy 一并带过去，mihomo 启动可跳过 HTTP 拉取。
     */
    fun prepareRootRuntime(context: Context, uuid: String): File {
        val imported = File(getWorkDir(context), "imported/$uuid")
        val runtime = getRuntimeDir(context, uuid)
        // 先清残留（可能是 root:root 遗孤，Kotlin 删不掉走 su）
        if (runtime.exists()) {
            if (!runtime.deleteRecursively()) {
                RootHelper.rmRfAsRoot(runtime.absolutePath)
            }
        }
        runtime.mkdirs()
        if (imported.exists()) {
            imported.copyRecursively(runtime, overwrite = true)
        }
        ensureGeodataLinks(context, runtime)
        return runtime
    }

    /**
     * ROOT 停止后清理 runtime/{uuid}/。必须在 mihomo 进程确认死亡后调用。
     * 运行期 mihomo 以 root 写入的文件 Kotlin 无法 unlink，直接走 su rm -rf。
     */
    fun cleanupRootRuntime(context: Context, uuid: String) {
        val runtime = getRuntimeDir(context, uuid)
        if (!runtime.exists()) return
        // app 权限能删的先删（省 su 调用）；失败走 root
        if (!runtime.deleteRecursively()) {
            RootHelper.rmRfAsRoot(runtime.absolutePath)
        }
    }

    /**
     * 兜底：擦净整个 runtime/ 目录。切换到 VPN 模式时调用，回收 root 遗孤。
     */
    fun cleanupAllRootRuntime(context: Context) {
        val runtime = File(getWorkDir(context), "runtime")
        if (!runtime.exists()) return
        if (!runtime.deleteRecursively()) {
            RootHelper.rmRfAsRoot(runtime.absolutePath)
        }
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
}
