package top.yukonga.mishka.platform

import androidx.compose.ui.graphics.ImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * 代理组图标加载器：单例 HttpClient + LRU 内存缓存 + 并发限流。
 *
 * 解决 ProxyScreen 原实现中每个图标新建/关闭 HttpClient、mutableMap 无并发保护、
 * 缓存无上限可能 OOM 的问题。加载顺序：内存 LRU → 磁盘缓存 → 网络下载。
 */
object IconLoader {

    private const val MAX_ENTRIES = 64
    private const val MAX_PARALLEL_DOWNLOADS = 3

    private val client = HttpClient()
    private val semaphore = Semaphore(MAX_PARALLEL_DOWNLOADS)
    private val mutex = Mutex()

    // 手写 LRU：LinkedHashMap 按插入顺序，命中后先 remove 再 put 即可提升为 MRU
    private val cache = LinkedHashMap<String, ImageBitmap>()

    suspend fun loadIcon(url: String): ImageBitmap? {
        if (url.isEmpty()) return null

        mutex.withLock {
            val hit = cache.remove(url)
            if (hit != null) {
                cache[url] = hit
                return hit
            }
        }

        // 磁盘缓存
        val diskBytes = runCatching { IconDiskCache.get(url) }.getOrNull()
        if (diskBytes != null) {
            val bitmap = runCatching { diskBytes.decodeToImageBitmap() }.getOrNull()
            if (bitmap != null) {
                put(url, bitmap)
                return bitmap
            }
        }

        // 网络下载（限流）
        return semaphore.withPermit {
            // 等待 permit 期间可能已被其它协程填充
            mutex.withLock { cache[url] }?.let { return@withPermit it }
            try {
                val bytes = client.get(url).readRawBytes()
                val bitmap = bytes.decodeToImageBitmap()
                runCatching { IconDiskCache.put(url, bytes) }
                put(url, bitmap)
                bitmap
            } catch (_: Exception) {
                null
            }
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

    suspend fun clear() {
        mutex.withLock { cache.clear() }
        runCatching { IconDiskCache.clear() }
    }
}
