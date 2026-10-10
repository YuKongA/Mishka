package top.yukonga.mishka.service

import android.content.Context
import android.os.Process
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.mishka.data.repository.ProfileProcessor
import top.yukonga.mishka.data.repository.SubscriptionRepositoryImpl
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * ROOT 停机前把 mihomo 写在 runtime/ 里的 provider 缓存回写到 imported/。
 * 进程已死才能调：cache.db 与 provider 文件还在被写时拷贝会留下半截文件。
 * 与 VPN 一样按工作目录里实际落下的文件回写，不重跑覆写脚本，也不删除 imported/ 里不再引用的旧文件。
 * config.yaml 变了就整单跳过，避免把旧沙箱写进运行期间的新提交。回写失败仍删除沙箱。
 *
 * 回写、删除、prepare 共用 [sandboxLock]。清理只认启动时记下的代次：监控的 release 在
 * NonCancellable 里，cancel 停不掉，代次已经被新 prepare 或删除订阅取代就不再 rm -rf。
 * 锁顺序是 processLock → sandboxLock → profileLock，别反着取。
 */
internal object RootRuntimeCache {

    private const val TAG = "RootRuntimeCache"

    private val sandboxLock = Mutex()
    private val epochs = ConcurrentHashMap<String, Long>()

    fun epochOf(uuid: String): Long = epochs[uuid] ?: 0L

    /**
     * 新鲜启动准备沙箱。代次在创建目录前递增，已经记下旧代次的清理不会删掉这份新目录。
     * 返回值就是这次代次，调用方要把它交给进程监控，不要事后再读 [epochOf]。
     */
    suspend fun prepare(context: Context, uuid: String): Long = sandboxLock.withLock {
        val next = epochOf(uuid) + 1L
        epochs[uuid] = next
        ProfileFileOps.prepareRootRuntime(context, uuid)
        next
    }

    /**
     * 删除订阅时清 runtime/。与 prepare 同一把锁，并废掉旧代次。
     * 必须挂起等锁：删除从主线程协程进来，回写持锁期间有最长 120s 的 shell。
     * 锁内不挂起，阻塞的 rm 放到 IO。
     */
    suspend fun discard(context: Context, uuid: String) {
        if (!isRuntimeUuid(uuid)) return
        withContext(Dispatchers.IO) {
            sandboxLock.withLock {
                epochs[uuid] = epochOf(uuid) + 1L
                ProfileFileOps.cleanupRootRuntime(context, uuid)
            }
        }
    }

    /** @return 这次清理仍拥有该代次并已删除沙箱。false 表示沙箱已换代，调用方不得再改全局状态。 */
    suspend fun release(
        context: Context,
        uuid: String,
        repository: SubscriptionRepositoryImpl,
        ownedEpoch: Long,
    ): Boolean = withContext(NonCancellable) {
        if (!isRuntimeUuid(uuid)) return@withContext false
        ProfileProcessor.withProcessLock {
            sandboxLock.withLock {
                if (epochOf(uuid) != ownedEpoch) {
                    Log.i(TAG, "skip stale runtime cleanup for $uuid")
                    return@withLock false
                }
                flushQuietly(context, uuid, repository)
                ProfileFileOps.cleanupRootRuntime(context, uuid)
                epochs[uuid] = ownedEpoch + 1L
                true
            }
        }
    }

    suspend fun releaseAll(
        context: Context,
        repository: SubscriptionRepositoryImpl,
    ) = withContext(NonCancellable) {
        ProfileProcessor.withProcessLock {
            sandboxLock.withLock {
                val uuids = ProfileFileOps.listRuntimeUuids(context).filter { isRuntimeUuid(it) }
                for (uuid in uuids) {
                    flushQuietly(context, uuid, repository)
                }
                for (uuid in epochs.keys + uuids) {
                    epochs[uuid] = epochOf(uuid) + 1L
                }
                ProfileFileOps.cleanupAllRootRuntime(context)
            }
        }
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
            // 调用方已持 processLock，再持 sandboxLock。这里只取 profileLock。
            // ProfileWorker 另建仓库，只持本实例的 profileLock 挡不住它换入 imported/。
            // Mutex 不可重入，锁内不能再调 loadRuntimeSubscription。
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
