package top.yukonga.mishka.platform

import java.io.File

actual object IconDiskCache {
    private var cacheDir: File? = null

    actual fun init(context: PlatformContext) {
        cacheDir = File(System.getProperty("user.home"), ".mishka/icons").apply { mkdirs() }
    }

    actual fun get(url: String): ByteArray? {
        val file = cacheFile(url) ?: return null
        return if (file.exists()) file.readBytes() else null
    }

    actual fun put(url: String, bytes: ByteArray) {
        cacheFile(url)?.writeBytes(bytes)
    }

    actual fun clear() {
        cacheDir?.listFiles()?.forEach { it.delete() }
    }

    private fun cacheFile(url: String): File? {
        val dir = cacheDir ?: return null
        return File(dir, url.hashCode().toUInt().toString(16))
    }
}
