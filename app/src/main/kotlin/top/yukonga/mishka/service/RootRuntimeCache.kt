package top.yukonga.mishka.service

import android.content.Context
import android.os.Process
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import top.yukonga.mishka.data.bridge.MishkaCoreBridge
import top.yukonga.mishka.data.bridge.ProviderCacheEntry
import top.yukonga.mishka.data.database.decodeOverrideIds
import top.yukonga.mishka.data.repository.ProfileProcessor
import top.yukonga.mishka.data.repository.SubscriptionRepositoryImpl
import top.yukonga.mishka.data.store.ProfileTransformWriter
import top.yukonga.mishka.domain.model.Subscription
import java.io.File
import java.security.MessageDigest

/**
 * ROOT 停机前把 mihomo 写在 runtime/ 里的 HTTP provider 缓存回写到 imported/。
 * 进程已死才能调：cache.db 与 provider 文件还在被写时拷贝会留下半截文件。
 * 回写只保留启动时记下、且 path 与 URL 仍一致的项；没有快照就跳过，不按停机时的覆写重算。
 * 回写失败仍删除沙箱，避免 root:root 残留挡住下一次启动。
 */
internal object RootRuntimeCache {

    private const val TAG = "RootRuntimeCache"
    private const val SNAPSHOT_NAME = ".mishka-provider-cache.json"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /**
     * 新鲜启动、进程起来之前调用。快照必须落在 runtime/：写进 imported/ 会被下一轮
     * prepareRootRuntime 拷走，停机时就分不清这是哪一次启动的定义。
     */
    fun capture(runtime: File, transform: File?, ageSecretKey: String) {
        if (!runtime.isDirectory) return
        try {
            val entries = MishkaCoreBridge.providerCachePaths(runtime, transform, ageSecretKey)
            ProfileFileOps.writeAtomically(
                File(runtime, SNAPSHOT_NAME),
                json.encodeToString(ListSerializer(ProviderCacheEntry.serializer()), entries),
            )
        } catch (e: Exception) {
            Log.w(TAG, "provider cache snapshot failed: ${e.message}")
        } catch (e: LinkageError) {
            Log.w(TAG, "provider cache snapshot unavailable: ${e.message}")
        }
    }

    suspend fun release(
        context: Context,
        uuid: String,
        repository: SubscriptionRepositoryImpl,
        writer: ProfileTransformWriter,
    ) = withContext(NonCancellable) {
        flushQuietly(context, uuid, repository, writer)
        ProfileFileOps.cleanupRootRuntime(context, uuid)
    }

    suspend fun releaseAll(
        context: Context,
        repository: SubscriptionRepositoryImpl,
        writer: ProfileTransformWriter,
    ) = withContext(NonCancellable) {
        for (uuid in ProfileFileOps.listRuntimeUuids(context)) {
            flushQuietly(context, uuid, repository, writer)
        }
        ProfileFileOps.cleanupAllRootRuntime(context)
    }

    private suspend fun flushQuietly(
        context: Context,
        uuid: String,
        repository: SubscriptionRepositoryImpl,
        writer: ProfileTransformWriter,
    ) {
        try {
            flush(context, uuid, repository, writer)
        } catch (e: Exception) {
            Log.w(TAG, "provider cache sync failed for $uuid: ${e.message}")
        } catch (e: LinkageError) {
            // 旧 libmihomo.so 没有路径导出时停机仍要删沙箱，不能把 Service 打死
            Log.w(TAG, "provider cache sync unavailable for $uuid: ${e.message}")
        }
    }

    private suspend fun flush(
        context: Context,
        uuid: String,
        repository: SubscriptionRepositoryImpl,
        writer: ProfileTransformWriter,
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
                    val entity = repository.queryImported(uuid) ?: return@withProfileLock
                    val runtimeConfig = File(runtime, "config.yaml")
                    val importedConfig = File(imported, "config.yaml")
                    if (!sameFileContent(runtimeConfig, importedConfig)) {
                        Log.i(TAG, "skip provider cache sync for $uuid: subscription config changed")
                        return@withProfileLock
                    }
                    val snapshot = readSnapshot(runtime)
                    if (snapshot == null) {
                        Log.i(TAG, "skip provider cache sync for $uuid: no startup snapshot")
                        return@withProfileLock
                    }
                    val subscription = Subscription(
                        id = uuid,
                        ageSecretKey = entity.ageSecretKey,
                        overrideIds = entity.overrideIds.decodeOverrideIds(),
                        overrideSortPreference = entity.overrideSortPreference.decodeOverrideIds(),
                    )
                    val transformRelative = "cache-sync/$uuid.transform.json"
                    val transformFile = File(File(context.filesDir, "mihomo"), transformRelative)
                    try {
                        val transformPath = writer.write(subscription, transformRelative)
                        val current = MishkaCoreBridge.providerCachePaths(
                            imported,
                            transformPath?.let(::File),
                            entity.ageSecretKey,
                        )
                        val rels = matchingCacheRels(snapshot, current)
                        val pairs = cachePairs(runtime, imported, rels)
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
                    } finally {
                        transformFile.delete()
                        transformFile.parentFile?.takeIf { it.list().isNullOrEmpty() }?.delete()
                    }
                }
            }
        }
    }

    private fun readSnapshot(runtime: File): List<ProviderCacheEntry>? {
        val file = File(runtime, SNAPSHOT_NAME)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(ListSerializer(ProviderCacheEntry.serializer()), file.readText())
        }.getOrElse {
            Log.w(TAG, "provider cache snapshot unreadable: ${it.message}")
            null
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

internal fun matchingCacheRels(
    started: List<ProviderCacheEntry>,
    current: List<ProviderCacheEntry>,
): List<String> {
    fun groups(entries: List<ProviderCacheEntry>): Map<String, Set<String>> {
        val out = linkedMapOf<String, MutableSet<String>>()
        for (entry in entries) {
            if (!isProviderCacheRel(entry.path)) continue
            out.getOrPut(entry.path) { linkedSetOf() }.add(entry.url)
        }
        return out
    }
    val now = groups(current)
    return groups(started).mapNotNull { (path, urls) ->
        val currentUrls = now[path] ?: return@mapNotNull null
        if (currentUrls != urls) return@mapNotNull null
        if (path == "cache.db" && urls != setOf("")) return@mapNotNull null
        path
    }.sorted()
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
