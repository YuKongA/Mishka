package top.yukonga.mishka.service

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files

/**
 * 订阅文件操作。三阶段目录：pending → processing → imported。
 * processing 是单例目录，串行使用。
 */
object ProfileFileOps {

    // 回滚目录带 uuid：进程死在两次 rename 之间时，靠它还原 imported/{uuid}/
    private const val COMMIT_STAGING = "commit.new"
    private const val COMMIT_OLD_PREFIX = "commit.old."

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

    fun hasValidConfig(context: Context, uuid: String?): Boolean {
        if (uuid.isNullOrEmpty()) return false
        val config = File(File(getWorkDir(context), "imported/$uuid"), "config.yaml")
        return config.isFile && config.length() > 0
    }

    /**
     * ROOT 模式运行时沙箱目录（mihomo 以 uid=0 在此写 provider/ruleset 缓存，不污染 imported/）。
     */
    fun getRuntimeDir(context: Context, uuid: String): File =
        File(getWorkDir(context), "runtime/$uuid")

    /** 不创建目录。回写缓存时不能把已删除的订阅目录又 mkdir 出来。 */
    fun peekImportedDir(context: Context, uuid: String): File =
        File(getWorkDir(context), "imported/$uuid")

    fun listRuntimeUuids(context: Context): List<String> {
        val runtime = File(getWorkDir(context), "runtime")
        return runtime.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()
    }

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

    /**
     * 原子覆盖写。`writeText` 是先截断再写，中途掉电会留下半个文件——对 override.user.json
     * 这类配置，解析失败等同于用户设置全丢。同目录 rename 在 ext4/f2fs 上原子，
     * rename 前 fsync 保证内容先于目录项落盘。
     */
    fun writeAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(content.toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("rename ${tmp.name} -> ${target.name} failed")
        }
    }

    fun writeProcessingConfig(workDir: String, content: String): File {
        val file = File(workDir, "config.yaml")
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    /** 清 processing/ 与提交残留；commit.old.{uuid} 在 imported/{uuid}/ 缺失时用于还原。 */
    fun cleanupProcessing(context: Context) {
        val workDir = getWorkDir(context)
        val processing = File(workDir, "processing")
        if (processing.exists()) processing.deleteRecursively()
        File(workDir, COMMIT_STAGING).deleteRecursively()
        workDir.listFiles { f -> f.isDirectory && f.name.startsWith(COMMIT_OLD_PREFIX) }
            ?.forEach { saved ->
                val imported = File(workDir, "imported/${saved.name.removePrefix(COMMIT_OLD_PREFIX)}")
                if (imported.exists()) removeMaybeRootOwned(saved) else saved.renameTo(imported)
            }
    }

    /**
     * 提交：processing/ 拷进 commit.new/，再 rename 换入 imported/{uuid}/，最后删 pending/{uuid}/。
     *
     * 拷贝排在所有删除之前，失败时 imported/{uuid}/ 仍完整——update 路径下它是唯一副本。
     */
    fun commitProcessingToImported(context: Context, uuid: String) {
        val workDir = getWorkDir(context)
        val processing = getProcessingDir(context)
        val imported = File(workDir, "imported/$uuid")
        val pending = File(workDir, "pending/$uuid")
        val staging = File(workDir, COMMIT_STAGING)
        val old = File(workDir, "$COMMIT_OLD_PREFIX$uuid")

        staging.deleteRecursively()
        removeMaybeRootOwned(old)
        try {
            staging.mkdirs()
            if (processing.exists()) processing.copyRecursively(staging, overwrite = true)
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }

        imported.parentFile?.mkdirs()
        val movedAside = imported.exists() && imported.renameTo(old)
        if (imported.exists()) {
            staging.deleteRecursively()
            throw IOException("Cannot move aside imported/$uuid")
        }
        if (!staging.renameTo(imported)) {
            if (movedAside) old.renameTo(imported)
            staging.deleteRecursively()
            throw IOException("Cannot swap in imported/$uuid")
        }
        if (movedAside) removeMaybeRootOwned(old)
        if (pending.exists()) pending.deleteRecursively()
    }

    /** 目录里可能有 root:root 文件，Kotlin 无权限 unlink 时走 su 兜底。 */
    private fun removeMaybeRootOwned(dir: File) {
        if (dir.exists() && !dir.deleteRecursively()) RootHelper.rmRfAsRoot(dir.absolutePath)
    }

    // === 删除与复制 ===

    fun deleteProfileDirs(context: Context, uuid: String) {
        val imported = File(getWorkDir(context), "imported/$uuid")
        val pending = File(getWorkDir(context), "pending/$uuid")
        if (imported.exists() && !imported.deleteRecursively()) {
            RootHelper.rmRfAsRoot(imported.absolutePath)
        }
        if (pending.exists()) pending.deleteRecursively()
        // 与 prepare/release 同一把锁。直接 rm -rf 会在新沙箱建好后补删。
        RootRuntimeCache.discard(context, uuid)
    }

    /**
     * 删除 imported/ 与 pending/ 下不属于 [knownUuids] 的目录，返回被删的 uuid。
     *
     * 删除订阅是「先删 DB 行 → 再删目录」两步，中间进程死亡就留下永远无人认领的目录；
     * 只有 DB 才知道哪些还算数，故按现存 uuid 反扫。
     */
    fun deleteOrphanProfileDirs(context: Context, knownUuids: Set<String>): List<String> {
        val workDir = getWorkDir(context)
        val orphans = listOf("imported", "pending")
            .flatMap { sub -> File(workDir, sub).listFiles()?.filter { it.isDirectory }.orEmpty() }
            .map { it.name }
            .distinct()
            .filter { it !in knownUuids }
        orphans.forEach { deleteProfileDirs(context, it) }
        return orphans
    }

    // === ROOT 运行时沙箱 ===

    /**
     * ROOT 启动前准备：清残留 → 从 imported/{uuid}/ 复制一份到 runtime/{uuid}/（app UID 写入）→ 重建 geodata 链接。
     * 复制保留 mtime：Fetcher.Initial 用它和 interval 比较，重置成「现在」会让 ROOT 每次都显得刚更新过。
     * 调用方必须是 [RootRuntimeCache.prepare]，由它持沙箱锁并递增代次。
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
            preserveCopiedMtimes(imported, runtime)
        }
        ensureGeodataLinks(context, runtime)
        return runtime
    }

    private fun preserveCopiedMtimes(source: File, dest: File) {
        source.walkTopDown().forEach { file ->
            if (Files.isSymbolicLink(file.toPath()) || !file.isFile) return@forEach
            val copied = File(dest, file.relativeTo(source).path)
            if (Files.isSymbolicLink(copied.toPath()) || !copied.isFile) return@forEach
            copied.setLastModified(file.lastModified())
        }
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

    // internal：WebDAV 备份（BackupManager）复用此名单排除订阅目录里的 GeoIP 链接/拷贝
    internal val GEODATA_FILES = listOf(
        "geoip.metadb",
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
}
