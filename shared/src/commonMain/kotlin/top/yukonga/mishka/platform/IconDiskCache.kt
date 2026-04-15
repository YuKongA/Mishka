package top.yukonga.mishka.platform

/**
 * 图标磁盘缓存。
 * 将代理组图标的字节数据持久化到磁盘，重启后仍可读取。
 */
expect object IconDiskCache {
    fun init(context: PlatformContext)
    fun get(url: String): ByteArray?
    fun put(url: String, bytes: ByteArray)
    fun clear()
}
