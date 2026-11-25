package top.yukonga.mishka.util

import androidx.compose.runtime.Immutable

private val PROTOCOL_REGEX = Regex("^\\[(\\w+)]")
private const val NO_RULE_USING_MARKER = " doesn't match any rule using "

/**
 * 解析后的 mihomo 日志条目，供展示与按语义字段搜索共用。
 */
@Immutable
data class ParsedLog(
    val protocol: String = "",
    val source: String = "",
    val target: String = "",
    val rule: String = "",
    val proxy: String = "",
    val body: String = "",
)

/**
 * 解析 mihomo 日志 payload。
 * 典型格式: [TCP] 198.18.0.1:45552 --> statusapi.micloud.xiaomi.com:443 match GeoSite(cn) using 全球直连[DIRECT]
 */
fun parseLogPayload(payload: String): ParsedLog {
    val raw = payload.trim()

    val protocolMatch = PROTOCOL_REGEX.find(raw)
    val protocol = protocolMatch?.groupValues?.get(1) ?: ""
    val rest = if (protocolMatch != null) raw.substring(protocolMatch.range.last + 1).trim() else raw
    val fallbackBody = rest.ifEmpty { raw }

    // 仅对正常 TCP/UDP 转发日志拆解五元组；dial 失败日志与 [DNS] 等模块日志保留完整内容
    if ((protocol != "TCP" && protocol != "UDP") || rest.startsWith("dial ")) {
        return ParsedLog(protocol = protocol, body = fallbackBody)
    }

    val arrowIdx = rest.indexOf("-->")
    if (arrowIdx < 0) return ParsedLog(protocol = protocol, body = fallbackBody)

    val source = rest.substring(0, arrowIdx).trim()
    val afterArrow = rest.substring(arrowIdx + 3).trim()

    val matchIdx = afterArrow.indexOf(" match ")
    if (matchIdx < 0) {
        val noRuleIdx = afterArrow.indexOf(NO_RULE_USING_MARKER)
        if (noRuleIdx >= 0) {
            return ParsedLog(
                protocol = protocol,
                source = source,
                target = afterArrow.substring(0, noRuleIdx).trim(),
                proxy = afterArrow.substring(noRuleIdx + NO_RULE_USING_MARKER.length).trim(),
                body = fallbackBody,
            )
        }
        val usingIdx = afterArrow.indexOf(" using ")
        if (usingIdx >= 0) {
            return ParsedLog(
                protocol = protocol,
                source = source,
                target = afterArrow.substring(0, usingIdx).trim(),
                proxy = afterArrow.substring(usingIdx + 7).trim(),
                body = fallbackBody,
            )
        }
        return ParsedLog(protocol = protocol, source = source, target = afterArrow, body = fallbackBody)
    }

    val target = afterArrow.substring(0, matchIdx).trim()
    val matchPart = afterArrow.substring(matchIdx + 7).trim()

    val usingIdx = matchPart.indexOf(" using ")
    val rule: String
    val proxy: String
    if (usingIdx >= 0) {
        rule = matchPart.substring(0, usingIdx).trim()
        proxy = matchPart.substring(usingIdx + 7).trim()
    } else {
        rule = matchPart
        proxy = ""
    }

    return ParsedLog(
        protocol = protocol,
        source = source,
        target = target,
        rule = rule,
        proxy = proxy,
        body = fallbackBody,
    )
}

/** 日志级别对应的展示标签（同时用于卡片胶囊与搜索匹配）。 */
fun logLevelTag(type: String): String = when (type.lowercase()) {
    "error" -> "ERROR"
    "warning" -> "WARN"
    "info" -> "INFO"
    "debug" -> "DEBUG"
    else -> "VERBOSE"
}
