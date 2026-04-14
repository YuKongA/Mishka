package top.yukonga.mishka.util

object FormatUtils {

    fun formatSpeed(bytesPerSecond: Long): String {
        return "${formatBytes(bytesPerSecond)}/s"
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }
        return if (value == value.toLong().toDouble()) {
            "${value.toLong()} ${units[unitIndex]}"
        } else {
            "%.1f ${units[unitIndex]}".format(value)
        }
    }

    fun formatLatency(delay: Int): String {
        return if (delay < 0) "-- ms" else "$delay ms"
    }

    fun formatUptime(seconds: Long): String {
        if (seconds < 60) return "${seconds}秒"
        if (seconds < 3600) return "${seconds / 60}分钟"
        if (seconds < 86400) return "${seconds / 3600}小时${(seconds % 3600) / 60}分"
        return "${seconds / 86400}天${(seconds % 86400) / 3600}时"
    }
}
