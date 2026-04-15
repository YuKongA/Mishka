package top.yukonga.mishka.util

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

object AppIconCache {
    private val maxMemory = Runtime.getRuntime().maxMemory() / 1024
    private val cacheSize = (maxMemory / 8).toInt()
    private val loadSemaphore = Semaphore(4)

    private val lruCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.allocationByteCount / 1024
        }
    }

    fun getFromCache(packageName: String): Bitmap? {
        synchronized(lruCache) { return lruCache.get(packageName) }
    }

    suspend fun loadIcon(context: Context, packageName: String, sizePx: Int): Bitmap {
        synchronized(lruCache) {
            val cached = lruCache.get(packageName)
            if (cached != null) return cached
        }

        return loadSemaphore.withPermit {
            synchronized(lruCache) {
                val cached = lruCache.get(packageName)
                if (cached != null) return@withPermit cached
            }

            withContext(Dispatchers.IO) {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                val bitmap = drawable.toBitmap(width = sizePx, height = sizePx)

                val gpuBitmap = try {
                    bitmap.copy(Bitmap.Config.HARDWARE, false)?.also {
                        bitmap.recycle()
                    } ?: bitmap.also { it.prepareToDraw() }
                } catch (_: Exception) {
                    bitmap.also { it.prepareToDraw() }
                }

                synchronized(lruCache) {
                    lruCache.put(packageName, gpuBitmap)
                }

                gpuBitmap
            }
        }
    }
}
