package top.yukonga.mishka.util

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * 把 ISO-8601 时间字符串（带 `Z` 或 `±HH:MM` 时区后缀）按系统本地时区格式化为 `MM-dd HH:mm`。
 *
 * - 空串或 Go 零值时间 `0001-...` 返回空串
 * - 解析失败返回原串（便于排查）
 */
fun formatIsoTimeAsLocalShort(isoTime: String): String {
    if (isoTime.isBlank() || isoTime.startsWith("0001-")) return ""
    return try {
        Instant.parse(isoTime).toLocalDateTime(TimeZone.currentSystemDefault()).formatShort()
    } catch (_: Exception) {
        isoTime
    }
}

/**
 * 把 epoch 毫秒时间戳按系统本地时区格式化为 `yyyy-MM-dd HH:mm`。
 */
fun formatEpochMillisAsLocal(timestamp: Long): String {
    return Instant.fromEpochMilliseconds(timestamp)
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .formatLong()
}

private fun LocalDateTime.formatShort(): String = buildString {
    append(month.number.toString().padStart(2, '0'))
    append('-')
    append(day.toString().padStart(2, '0'))
    append(' ')
    append(hour.toString().padStart(2, '0'))
    append(':')
    append(minute.toString().padStart(2, '0'))
}

private fun LocalDateTime.formatLong(): String = buildString {
    append(year.toString().padStart(4, '0'))
    append('-')
    append(month.number.toString().padStart(2, '0'))
    append('-')
    append(day.toString().padStart(2, '0'))
    append(' ')
    append(hour.toString().padStart(2, '0'))
    append(':')
    append(minute.toString().padStart(2, '0'))
}
