package top.yukonga.mishka.service

import android.content.Context
import android.os.Process
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import top.yukonga.mishka.data.repository.ProfileProcessor
import top.yukonga.mishka.data.repository.SubscriptionRepositoryImpl
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/**
 * ROOT 停机前把 mihomo 写在 runtime/ 里的 provider 缓存回写到 imported/。
 * 进程已死才能调：cache.db 与 provider 文件还在被写时拷贝会留下半截文件。
 * 与 VPN 一样按工作目录里实际落下的文件回写，不重跑覆写脚本，也不删除 imported/ 里不再引用的旧文件。
 * config.yaml 变了就整单跳过，避免把旧沙箱写进运行期间的新提交。回写失败仍删除沙箱。
 */
internal object RootRuntimeCache {

    private const val TAG = "RootRuntimeCache"

    suspend fun release(
        context: Context,
        uuid: String,
        repository: SubscriptionRepositoryImpl,
    ) = withContext(NonCancellable) {
        flushQuietly(context, uuid, repository)
        ProfileFileOps.cleanupRootRuntime(context, uuid)
    }

    suspend fun releaseAll(
        context: Context,
        repository: SubscriptionRepositoryImpl,
    ) = withContext(NonCancellable) {
        for (uuid in ProfileFileOps.listRuntimeUuids(context)) {
            flushQuietly(context, uuid, repository)
        }
        ProfileFileOps.cleanupAllRootRuntime(context)
    }

    private suspend fun flushQuietly(
        context: Context,
        uuid: String,
        repository: SubscriptionRepositoryImpl,
    ) {
        try {
            flush(context, uuid, repository)
        } catch (e: Exception) {
            Log.w(TAG, "provider cache sync failed for $uuid: ${e.message}")
        }
    }

    private suspend fun flush(
        context: Context,
        uuid: String,
        repository: SubscriptionRepositoryImpl,
    ) {
        if (!isRuntimeUuid(uuid)) return
        val runtime = ProfileFileOps.getRuntimeDir(context, uuid)
        val imported = ProfileFileOps.peekImportedDir(context, uuid)
        if (!runtime.isDirectory || !imported.isDirectory) return
        withContext(Dispatchers.IO) {
            // ProfileWorker 另建仓库，只持本实例的 profileLock 挡不住它换入 imported/。
            // 先 processLock 再 profileLock，与导入提交同一顺序。Mutex 不可重入，
            // 锁内不能再调 loadRuntimeSubscription。
            ProfileProcessor.withProcessLock {
                repository.withProfileLock {
                    if (!runtime.isDirectory || !imported.isDirectory) return@withProfileLock
                    if (repository.queryImported(uuid) == null) return@withProfileLock
                    val runtimeConfig = File(runtime, "config.yaml")
                    val importedConfig = File(imported, "config.yaml")
                    if (!sameFileContent(runtimeConfig, importedConfig)) {
                        Log.i(TAG, "skip provider cache sync for $uuid: subscription config changed")
                        return@withProfileLock
                    }
                    val pairs = cachePairs(runtime, imported, providerCacheRels(runtime))
                    if (pairs.isEmpty()) return@withProfileLock
                    val copied = RootHelper.syncRegularFiles(
                        Process.myUid(),
                        runtime.absolutePath,
                        imported.absolutePath,
                        pairs,
                    )
                    if (!copied) {
                        Log.w(TAG, "provider cache sync incomplete for $uuid (${pairs.size} files)")
                    } else {
                        Log.i(TAG, "synced provider cache for $uuid (${pairs.size} files)")
                    }
                }
            }
        }
    }

    private fun cachePairs(runtime: File, imported: File, paths: List<String>): List<Pair<String, String>> {
        val runtimeRoot = runtime.absolutePath
        val importedRoot = imported.absolutePath
        return paths.mapNotNull { rel ->
            if (!isProviderCacheRel(rel)) return@mapNotNull null
            val src = File(runtime, rel)
            val dst = File(imported, rel)
            if (!isInside(src, runtimeRoot) || !isInside(dst, importedRoot)) return@mapNotNull null
            src.absolutePath to dst.absolutePath
        }
    }

    private fun isInside(file: File, root: String): Boolean {
        if (root.isEmpty()) return false
        val rootPrefix = root.trimEnd('/') + "/"
        val path = file.absolutePath
        return path == root || path.startsWith(rootPrefix)
    }

    private fun sameFileContent(left: File, right: File): Boolean {
        if (!left.isFile || !right.isFile || left.length() != right.length()) return false
        return sha256(left).contentEquals(sha256(right))
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }
}

/** runtime/ 里 mihomo 实际写下的普通文件。不重跑脚本：脚本新增的 path 不在磁盘 config.yaml 里。 */
internal fun providerCacheRels(runtime: File): List<String> {
    val root = runtime.toPath()
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    return runtime.walkTopDown()
        .onEnter { dir -> !Files.isSymbolicLink(dir.toPath()) }
        .mapNotNull { file ->
            val path = file.toPath()
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@mapNotNull null
            val rel = root.relativize(path).toString().replace('\\', '/')
            rel.takeIf { isProviderCacheRel(it) }
        }
        .sorted()
        .toList()
}

private fun isRuntimeUuid(uuid: String): Boolean {
    if (uuid.isEmpty() || uuid.length > 128 || uuid == "." || uuid == "..") return false
    return uuid.none { it == '/' || it == '\\' || it == '\u0000' || it == '\n' || it == '\r' }
}

internal fun isProviderCacheRel(path: String): Boolean {
    if (path.isEmpty() || path.length > 4096) return false
    if (path.any { it == '\\' || it == '\u0000' || it == '\n' || it == '\r' }) return false
    if (path.startsWith("/")) return false
    val parts = path.split('/')
    if (parts.any { it.isEmpty() || it == "." || it == ".." }) return false
    return parts.last() !in PROVIDER_CACHE_DENY_BASES
}

private val PROVIDER_CACHE_DENY_BASES = setOf(
    ".mishka-provider-cache.json",
    "config.yaml",
    "mihomo.log",
    "geoip.metadb",
    "geoip.db",
    "geoip.dat",
    "GeoIP.dat",
    "Country.mmdb",
    "country.mmdb",
    "geosite.dat",
    "GeoSite.dat",
    "ASN.mmdb",
    "asn.mmdb",
)
