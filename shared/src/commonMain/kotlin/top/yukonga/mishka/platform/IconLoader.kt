package top.yukonga.mishka.platform

import androidx.compose.ui.graphics.ImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 代理组图标加载器：LRU 内存缓存 + 磁盘缓存 + 并发限流下载。
 *
 * 图标 URL 多为境外 CDN（raw.githubusercontent.com / jsDelivr），而 Mishka 自身
 * 永远绕过 TUN/VPN 直连，代理运行中时须显式走 mihomo mixed-port 下载，
 * 由 [setProxyResolver] 注入的解析器决定；未注入或代理未运行时直连。
 *
 * 失败 URL 记入负缓存（[FAILURE_RETRY_INTERVAL] 内不重试），避免 lazy item
 * 滚出/滚回视口时把失败请求重放一遍、占满下载 permit。
 */
object IconLoader {

    private const val MAX_ENTRIES = 64
    private const val MAX_FAILURE_ENTRIES = 128
    private const val MAX_PARALLEL_DOWNLOADS = 3
    private val FAILURE_RETRY_INTERVAL = 60.seconds
    private val PROXY_RESOLVE_CACHE_TTL = 10.seconds

    private val semaphore = Semaphore(MAX_PARALLEL_DOWNLOADS)
    private val mutex = Mutex()

    // 手写 LRU：LinkedHashMap 按插入顺序，命中后先 remove 再 put 即可提升为 MRU
    private val cache = LinkedHashMap<String, ImageBitmap>()

    // 负缓存：url → 失败时刻，TTL 内直接返回 null 不发请求
    private val failures = LinkedHashMap<String, TimeMark>()

    private var proxyResolver: (suspend () -> String?)? = null

    // 短 TTL 缓存解析结果：一屏图标并发下载时避免每个都查一次 mihomo API
    private var resolvedProxy: Pair<TimeMark, String?>? = null

    // 直连 client 常驻；代理 client 随 proxy URL 变化重建（close 旧建新），均由 mutex 守护
    private var directClient: HttpClient? = null
    private var proxyClient: Pair<String, HttpClient>? = null

    /** 注入代理解析器（返回 `http://127.0.0.1:<mixed-port>` 或 null 直连），应用初始化时调用一次。 */
    fun setProxyResolver(resolver: suspend () -> String?) {
        proxyResolver = resolver
    }

    suspend fun loadIcon(url: String): ImageBitmap? {
        if (url.isEmpty()) return null

        mutex.withLock {
            val hit = cache.remove(url)
            if (hit != null) {
                cache[url] = hit
                return hit
            }
            val failedAt = failures[url]
            if (failedAt != null) {
                if (failedAt.elapsedNow() < FAILURE_RETRY_INTERVAL) return null
                failures.remove(url)
            }
        }

        return withContext(Dispatchers.IO) {
            // 磁盘缓存
            val diskBytes = runCatching { IconDiskCache.get(url) }.getOrNull()
            if (diskBytes != null) {
                val bitmap = runCatching { diskBytes.decodeToImageBitmap() }.getOrNull()
                if (bitmap != null) {
                    put(url, bitmap)
                    return@withContext bitmap
                }
            }

            // 网络下载（限流）
            semaphore.withPermit {
                // 等待 permit 期间可能已被其它协程填充
                mutex.withLock { cache[url] }?.let { return@withPermit it }
                try {
                    val client = obtainClient()
                    val bytes = client.get(url).readRawBytes()
                    val bitmap = bytes.decodeToImageBitmap()
                    runCatching { IconDiskCache.put(url, bytes) }
                    put(url, bitmap)
                    bitmap
                } catch (_: Exception) {
                    markFailed(url)
                    null
                }
            }
        }
    }

    /** 按当前代理状态取 client：proxy URL 变化时 close 旧 client 重建。 */
    private suspend fun obtainClient(): HttpClient {
        val proxyUrl = resolveProxy()
        return mutex.withLock {
            if (proxyUrl == null) {
                directClient ?: buildClient(null).also { directClient = it }
            } else {
                val current = proxyClient
                if (current != null && current.first == proxyUrl) {
                    current.second
                } else {
                    current?.second?.close()
                    buildClient(proxyUrl).also { proxyClient = proxyUrl to it }
                }
            }
        }
    }

    private suspend fun resolveProxy(): String? {
        val resolver = proxyResolver ?: return null
        mutex.withLock {
            val cached = resolvedProxy
            if (cached != null && cached.first.elapsedNow() < PROXY_RESOLVE_CACHE_TTL) {
                return cached.second
            }
        }
        val resolved = runCatching { resolver() }.getOrNull()
        mutex.withLock { resolvedProxy = TimeSource.Monotonic.markNow() to resolved }
        return resolved
    }

    private fun buildClient(proxyUrl: String?) = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 15_000
        }
        if (proxyUrl != null) {
            engine { proxy = ProxyBuilder.http(Url(proxyUrl)) }
        }
    }

    private suspend fun put(url: String, bitmap: ImageBitmap) {
        mutex.withLock {
            cache[url] = bitmap
            while (cache.size > MAX_ENTRIES) {
                val eldest = cache.keys.iterator().next()
                cache.remove(eldest)
            }
        }
    }

    private suspend fun markFailed(url: String) {
        mutex.withLock {
            failures.remove(url)
            failures[url] = TimeSource.Monotonic.markNow()
            while (failures.size > MAX_FAILURE_ENTRIES) {
                val eldest = failures.keys.iterator().next()
                failures.remove(eldest)
            }
        }
    }

    suspend fun clear() {
        mutex.withLock {
            cache.clear()
            failures.clear()
        }
        runCatching { withContext(Dispatchers.IO) { IconDiskCache.clear() } }
    }
}
