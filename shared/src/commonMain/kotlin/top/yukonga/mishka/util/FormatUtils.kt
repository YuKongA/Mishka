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
}
