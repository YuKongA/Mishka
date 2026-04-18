package top.yukonga.mishka.util

/**
 * 给可空的异常 message 一个兜底，避免 UI 拼出 "null"。
 * 优先 message，其次类名，最后通用 "Unknown error"。
 */
fun Throwable.describe(): String =
    message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "Unknown error"
