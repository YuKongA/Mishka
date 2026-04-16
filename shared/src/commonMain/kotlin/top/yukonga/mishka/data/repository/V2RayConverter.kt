package top.yukonga.mishka.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLDecoder

/**
 * V2Ray 订阅格式检测和转换。
 * 将 base64 编码的 V2Ray 链接列表转换为 mihomo YAML 配置。
 * 对齐 CMFA converter.go 的协议支持。
 */
object V2RayConverter {

    private val YAML_TOP_KEYS = setOf(
        "proxies", "proxy-providers", "proxy-groups",
        "rules", "rule-providers", "dns", "tun",
        "mixed-port", "port", "socks-port",
    )

    private val PROTOCOL_PREFIXES = setOf(
        "vmess://", "vless://", "trojan://", "ss://", "ssr://",
        "hysteria://", "hysteria2://", "hy2://", "tuic://",
        "socks5://", "http://", "https://", "anytls://", "mierus://",
    )

    /**
     * 检测内容是否为 V2Ray 订阅格式（非 YAML）。
     */
    fun isV2RaySubscription(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return false

        // 包含 YAML 顶层 key 则认为是 YAML
        for (line in trimmed.lineSequence()) {
            val stripped = line.trimStart()
            if (stripped.isEmpty() || stripped.startsWith("#")) continue
            val indent = line.length - stripped.length
            if (indent > 0) continue
            val key = stripped.substringBefore(":").trim()
            if (key in YAML_TOP_KEYS) return false
        }

        // 直接包含协议前缀
        if (PROTOCOL_PREFIXES.any { trimmed.contains(it, ignoreCase = true) }) return true

        // 看起来像 base64（只含合法字符）
        val cleaned = trimmed.replace(Regex("[\\s\\r\\n]"), "")
        if (cleaned.length > 20 && cleaned.all { it in BASE64_CHARS }) {
            return try {
                val decoded = decodeBase64(cleaned)
                decoded.isNotEmpty() && PROTOCOL_PREFIXES.any { decoded.contains(it, ignoreCase = true) }
            } catch (_: Exception) {
                false
            }
        }

        return false
    }

    /**
     * 将 V2Ray 订阅内容转换为 mihomo YAML 配置。
     * @throws IllegalArgumentException 无法解析任何有效节点
     */
    fun convert(content: String): String {
        val decoded = decodeBase64(content.trim())
        val lines = decoded.split("\n").map { it.trim().trimEnd('\r') }.filter { it.isNotEmpty() }
        val names = mutableMapOf<String, Int>()
        val proxies = mutableListOf<Map<String, Any>>()

        for (line in lines) {
            val sepIndex = line.indexOf("://")
            if (sepIndex < 0) continue
            val scheme = line.substring(0, sepIndex).lowercase()

            try {
                val parsed = when (scheme) {
                    "hysteria" -> parseHysteria(line, names)
                    "hysteria2", "hy2" -> parseHysteria2(line, names)
                    "tuic" -> parseTuic(line, names)
                    "trojan" -> parseTrojan(line, names)
                    "vless" -> parseVless(line, names)
                    "vmess" -> parseVmess(line, names)
                    "ss" -> parseShadowsocks(line, names)
                    "ssr" -> parseShadowsocksR(line, names)
                    "socks", "socks5", "socks5h", "http", "https" -> parseSocksHttp(line, scheme, names)
                    "anytls" -> parseAnyTls(line, names)
                    else -> null
                }
                if (parsed != null) {
                    if (parsed is List<*>) {
                        @Suppress("UNCHECKED_CAST")
                        proxies.addAll(parsed as List<Map<String, Any>>)
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        proxies.add(parsed as Map<String, Any>)
                    }
                }
            } catch (_: Exception) {
                // 跳过解析失败的行
            }
        }

        require(proxies.isNotEmpty()) { "订阅中未找到有效的代理节点" }

        return generateConfig(proxies)
    }

    // === 协议解析 ===

    private fun parseHysteria(line: String, names: MutableMap<String, Int>): Map<String, Any> {
        val uri = URI(line)
        val query = parseQuery(uri.rawQuery)
        return buildMap {
            put("name", uniqueName(names, urlDecode(uri.fragment ?: "")))
            put("type", "hysteria")
            put("server", uri.host)
            put("port", uri.port.toString())
            query["peer"]?.let { put("sni", it) }
            query["obfs"]?.let { put("obfs", it) }
            query["alpn"]?.let { put("alpn", it.split(",")) }
            query["auth"]?.let { put("auth_str", it) }
            query["protocol"]?.let { put("protocol", it) }
            put("up", query["up"] ?: query["upmbps"] ?: "")
            put("down", query["down"] ?: query["downmbps"] ?: "")
            query["insecure"]?.let { put("skip-cert-verify", it == "true" || it == "1") }
        }
    }

    private fun parseHysteria2(line: String, names: MutableMap<String, Int>): Map<String, Any> {
        val uri = URI(line)
        val query = parseQuery(uri.rawQuery)
        return buildMap {
            put("name", uniqueName(names, urlDecode(uri.fragment ?: "")))
            put("type", "hysteria2")
            put("server", uri.host)
            put("port", if (uri.port > 0) uri.port.toString() else "443")
            query["obfs"]?.let { put("obfs", it) }
            query["obfs-password"]?.let { put("obfs-password", it) }
            query["sni"]?.let { put("sni", it) }
            query["insecure"]?.let { put("skip-cert-verify", it == "true" || it == "1") }
            query["alpn"]?.let { put("alpn", it.split(",")) }
            val userInfo = uri.rawUserInfo
            if (!userInfo.isNullOrEmpty()) {
                put("password", urlDecode(userInfo))
            }
            query["pinSHA256"]?.let { put("fingerprint", it) }
            query["down"]?.let { put("down", it) }
            query["up"]?.let { put("up", it) }
        }
    }

    private fun parseTuic(line: String, names: MutableMap<String, Int>): Map<String, Any> {
        val uri = URI(line)
        val query = parseQuery(uri.rawQuery)
        return buildMap {
            put("name", uniqueName(names, urlDecode(uri.fragment ?: "")))
            put("type", "tuic")
            put("server", uri.host)
            put("port", uri.port.toString())
            put("udp", true)
            val userInfo = uri.rawUserInfo ?: ""
            if (userInfo.contains(":")) {
                val parts = userInfo.split(":", limit = 2)
                put("uuid", urlDecode(parts[0]))
                put("password", urlDecode(parts[1]))
            } else {
                put("token", urlDecode(userInfo))
            }
            query["congestion_control"]?.let { put("congestion-controller", it) }
            query["alpn"]?.let { put("alpn", it.split(",")) }
            query["sni"]?.let { put("sni", it) }
            if (query["disable_sni"] == "1") put("disable-sni", true)
            query["udp_relay_mode"]?.let { put("udp-relay-mode", it) }
        }
    }

    private fun parseTrojan(line: String, names: MutableMap<String, Int>): Map<String, Any> {
        val uri = URI(line)
        val query = parseQuery(uri.rawQuery)
        return buildMap {
            put("name", uniqueName(names, urlDecode(uri.fragment ?: "")))
            put("type", "trojan")
            put("server", uri.host)
            put("port", uri.port.toString())
            put("password", urlDecode(uri.rawUserInfo ?: ""))
            put("udp", true)
            query["allowInsecure"]?.let { put("skip-cert-verify", it == "true" || it == "1") }
            query["sni"]?.let { put("sni", it) }
            query["alpn"]?.let { put("alpn", it.split(",")) }
            val network = query["type"]?.lowercase() ?: ""
            if (network.isNotEmpty()) {
                put("network", network)
                when (network) {
                    "ws" -> {
                        val wsOpts = mutableMapOf<String, Any>()
                        query["path"]?.let { wsOpts["path"] = it }
                        query["host"]?.let { wsOpts["headers"] = mapOf("Host" to it) }
                        if (wsOpts.isNotEmpty()) put("ws-opts", wsOpts)
                    }

                    "grpc" -> {
                        query["serviceName"]?.let {
                            put("grpc-opts", mapOf("grpc-service-name" to it))
                        }
                    }
                }
            }
            val fp = query["fp"]
            put("client-fingerprint", if (fp.isNullOrEmpty()) "chrome" else fp)
        }
    }

    private fun parseVless(line: String, names: MutableMap<String, Int>): Map<String, Any> {
        val uri = URI(line)
        val query = parseQuery(uri.rawQuery)
        return buildMap {
            put("name", uniqueName(names, urlDecode(uri.fragment ?: "")))
            put("type", "vless")
            put("server", uri.host)
            put("port", uri.port.toString())
            put("uuid", urlDecode(uri.rawUserInfo ?: ""))
            put("udp", true)
            query["flow"]?.let { put("flow", it.lowercase()) }
            query["encryption"]?.let { put("encryption", it) }
            handleVShareLink(this, query)
        }
    }

    private fun parseVmess(line: String, names: MutableMap<String, Int>): Map<String, Any>? {
        val body = line.substringAfter("vmess://")

        // 尝试 V2RayN JSON 格式（base64 编码的 JSON）
        val decoded = try {
            decodeBase64(body)
        } catch (_: Exception) {
            null
        }

        if (decoded != null) {
            try {
                val json = Json { ignoreUnknownKeys = true }
                val values = json.parseToJsonElement(decoded).jsonObject
                return parseVmessJson(values, names)
            } catch (_: Exception) {
            }
        }

        // Xray VMessAEAD URL 格式
        return try {
            val uri = URI(line)
            val query = parseQuery(uri.rawQuery)
            buildMap {
                put("name", uniqueName(names, urlDecode(uri.fragment ?: "")))
                put("type", "vmess")
                put("server", uri.host)
                put("port", uri.port.toString())
                put("uuid", urlDecode(uri.rawUserInfo ?: ""))
                put("alterId", 0)
                put("cipher", query["encryption"] ?: "auto")
                put("udp", true)
                handleVShareLink(this, query)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseVmessJson(values: JsonObject, names: MutableMap<String, Int>): Map<String, Any> {
        val ps = values["ps"]?.jsonPrimitive?.content ?: ""
        return buildMap {
            put("name", uniqueName(names, ps))
            put("type", "vmess")
            put("server", values.str("add"))
            put("port", values.str("port"))
            put("uuid", values.str("id"))
            put("alterId", values.str("aid").toIntOrNull() ?: 0)
            put("udp", true)
            put("xudp", true)
            put("tls", false)
            put("skip-cert-verify", false)

            val cipher = values.str("scy")
            put("cipher", cipher.ifEmpty { "auto" })

            val sni = values.str("sni")
            if (sni.isNotEmpty()) put("servername", sni)

            var network = values.str("net").lowercase()
            if (values.str("type") == "http") {
                network = "http"
            } else if (network == "http") {
                network = "h2"
            }
            if (network.isNotEmpty()) put("network", network)

            val tls = values.str("tls").lowercase()
            if (tls.endsWith("tls")) {
                put("tls", true)
                val alpn = values.str("alpn")
                if (alpn.isNotEmpty()) put("alpn", alpn.split(","))
            }

            val host = values.str("host")
            val path = values.str("path")

            when (network) {
                "http" -> {
                    val httpOpts = mutableMapOf<String, Any>()
                    if (host.isNotEmpty()) httpOpts["headers"] = mapOf("Host" to listOf(host))
                    httpOpts["path"] = listOf(path.ifEmpty { "/" })
                    put("http-opts", httpOpts)
                }

                "h2" -> {
                    val h2Opts = mutableMapOf<String, Any>()
                    if (host.isNotEmpty()) h2Opts["headers"] = mapOf("Host" to listOf(host))
                    h2Opts["path"] = path
                    put("h2-opts", h2Opts)
                }

                "ws", "httpupgrade" -> {
                    val wsOpts = mutableMapOf<String, Any>()
                    val headers = mutableMapOf<String, Any>()
                    if (host.isNotEmpty()) headers["Host"] = host
                    wsOpts["path"] = path.ifEmpty { "/" }
                    if (headers.isNotEmpty()) wsOpts["headers"] = headers
                    put("ws-opts", wsOpts)
                }

                "grpc" -> {
                    put("grpc-opts", mapOf("grpc-service-name" to path))
                }
            }
        }
    }

    private fun parseShadowsocks(line: String, names: MutableMap<String, Int>): Map<String, Any>? {
        val uri = try {
            URI(line)
        } catch (_: Exception) {
            return null
        }
        val name = uniqueName(names, urlDecode(uri.fragment ?: ""))
        var host = uri.host
        var port = uri.port
        var cipher: String
        var password: String

        val userInfo = uri.rawUserInfo
        if (userInfo != null && userInfo.contains(":")) {
            // 格式: ss://method:password@host:port
            val decoded = urlDecode(userInfo)
            val parts = decoded.split(":", limit = 2)
            cipher = parts[0]
            password = parts.getOrElse(1) { "" }
        } else if (userInfo != null) {
            // 格式: ss://base64(method:password)@host:port 或 ss://base64(全部)
            val decoded = try {
                decodeBase64(userInfo)
            } catch (_: Exception) {
                urlDecode(userInfo)
            }
            if (decoded.contains(":") && decoded.contains("@")) {
                // base64(method:password@host:port)
                val parts = decoded.split("@", limit = 2)
                val methodPass = parts[0].split(":", limit = 2)
                cipher = methodPass[0]
                password = methodPass.getOrElse(1) { "" }
                if (parts.size > 1) {
                    val hostPort = parts[1].split(":", limit = 2)
                    host = hostPort[0]
                    port = hostPort.getOrElse(1) { "" }.toIntOrNull() ?: port
                }
            } else if (decoded.contains(":")) {
                val parts = decoded.split(":", limit = 2)
                cipher = parts[0]
                password = parts.getOrElse(1) { "" }
            } else {
                return null
            }
        } else if (host == null && port < 0) {
            // 格式: ss://base64(method:password@host:port)#name
            val body = line.substringAfter("ss://").substringBefore("#")
            val decoded = try {
                decodeBase64(body)
            } catch (_: Exception) {
                return null
            }
            val reparse = try {
                URI("ss://$decoded")
            } catch (_: Exception) {
                return null
            }
            host = reparse.host ?: return null
            port = reparse.port
            val reuserInfo = urlDecode(reparse.rawUserInfo ?: return null)
            val parts = reuserInfo.split(":", limit = 2)
            cipher = parts[0]
            password = parts.getOrElse(1) { "" }
        } else {
            return null
        }

        if (host == null || port < 0) return null
        val query = parseQuery(uri.rawQuery)

        return buildMap {
            put("name", name)
            put("type", "ss")
            put("server", host)
            put("port", port.toString())
            put("cipher", cipher)
            put("password", password)
            put("udp", true)
            if (query["udp-over-tcp"] == "true" || query["uot"] == "1") {
                put("udp-over-tcp", true)
            }
            // plugin 支持
            val plugin = query["plugin"] ?: ""
            if (plugin.contains(";")) {
                val pluginParts = plugin.split(";")
                val pluginName = pluginParts[0]
                val pluginParams = mutableMapOf<String, String>()
                pluginParts.drop(1).forEach { param ->
                    val kv = param.split("=", limit = 2)
                    if (kv.size == 2) pluginParams[kv[0]] = kv[1]
                }
                when {
                    pluginName.contains("obfs") -> {
                        put("plugin", "obfs")
                        put(
                            "plugin-opts", mapOf(
                                "mode" to (pluginParams["obfs"] ?: ""),
                                "host" to (pluginParams["obfs-host"] ?: ""),
                            )
                        )
                    }

                    pluginName.contains("v2ray-plugin") -> {
                        put("plugin", "v2ray-plugin")
                        put(
                            "plugin-opts", mapOf(
                                "mode" to (pluginParams["mode"] ?: ""),
                                "host" to (pluginParams["host"] ?: ""),
                                "path" to (pluginParams["path"] ?: ""),
                                "tls" to plugin.contains("tls"),
                            )
                        )
                    }
                }
            }
        }
    }

    private fun parseShadowsocksR(line: String, names: MutableMap<String, Int>): Map<String, Any>? {
        val body = line.substringAfter("ssr://")
        val decoded = try {
            decodeBase64(body)
        } catch (_: Exception) {
            return null
        }

        // ssr://host:port:protocol:method:obfs:base64pass/?obfsparam=...&protoparam=...&remarks=...
        val parts = decoded.split("/?", limit = 2)
        if (parts.isEmpty()) return null

        val mainParts = parts[0].split(":")
        if (mainParts.size != 6) return null

        val host = mainParts[0]
        val port = mainParts[1]
        val protocol = mainParts[2]
        val method = mainParts[3]
        val obfs = mainParts[4]
        val password = decodeUrlSafe(mainParts[5])

        val query = if (parts.size > 1) parseQuery(parts[1]) else emptyMap()
        val remarks = decodeUrlSafe(query["remarks"] ?: "")
        val obfsParam = decodeUrlSafe(query["obfsparam"] ?: "")
        val protocolParam = decodeUrlSafe(query["protoparam"] ?: "")

        return buildMap {
            put("name", uniqueName(names, remarks))
            put("type", "ssr")
            put("server", host)
            put("port", port)
            put("cipher", method)
            put("password", password)
            put("obfs", obfs)
            put("protocol", protocol)
            put("udp", true)
            if (obfsParam.isNotEmpty()) put("obfs-param", obfsParam)
            if (protocolParam.isNotEmpty()) put("protocol-param", protocolParam)
        }
    }

    private fun parseSocksHttp(
        line: String,
        scheme: String,
        names: MutableMap<String, Int>,
    ): Map<String, Any>? {
        val uri = try {
            URI(line)
        } catch (_: Exception) {
            return null
        }
        val server = uri.host ?: return null
        val port = uri.port
        if (port < 0) return null
        val remarks = urlDecode(uri.fragment ?: "$server:$port")
        val name = uniqueName(names, remarks)

        var username = ""
        var password = ""
        val rawUser = uri.rawUserInfo
        if (!rawUser.isNullOrEmpty()) {
            val decoded = try {
                decodeBase64(rawUser)
            } catch (_: Exception) {
                urlDecode(rawUser)
            }
            val parts = decoded.split(":", limit = 2)
            username = parts[0]
            password = parts.getOrElse(1) { "" }
        }

        val type = when (scheme) {
            "socks", "socks5", "socks5h" -> "socks5"
            "http", "https" -> "http"
            else -> scheme
        }

        return buildMap {
            put("name", name)
            put("type", type)
            put("server", server)
            put("port", port.toString())
            if (username.isNotEmpty()) put("username", username)
            if (password.isNotEmpty()) put("password", password)
            put("skip-cert-verify", true)
            if (scheme == "https") put("tls", true)
        }
    }

    private fun parseAnyTls(line: String, names: MutableMap<String, Int>): Map<String, Any>? {
        val uri = try {
            URI(line)
        } catch (_: Exception) {
            return null
        }
        val server = uri.host ?: return null
        val port = uri.port
        if (port < 0) return null
        val query = parseQuery(uri.rawQuery)

        val rawUser = uri.rawUserInfo ?: ""
        val password = if (rawUser.contains(":")) {
            urlDecode(rawUser.substringAfter(":"))
        } else {
            urlDecode(rawUser)
        }

        val remarks = urlDecode(uri.fragment ?: "$server:$port")

        return buildMap {
            put("name", uniqueName(names, remarks))
            put("type", "anytls")
            put("server", server)
            put("port", port.toString())
            put("password", password)
            put("udp", true)
            query["sni"]?.let { put("sni", it) }
            query["hpkp"]?.let { put("fingerprint", it) }
            if (query["insecure"] == "1") put("skip-cert-verify", true)
        }
    }

    // === V/VMess 共享链接公共处理（对齐 CMFA v.go handleVShareLink） ===

    private fun handleVShareLink(map: MutableMap<String, Any>, query: Map<String, String>) {
        val tls = query["security"]?.lowercase() ?: ""
        if (tls.endsWith("tls") || tls == "reality") {
            map["tls"] = true
            val fp = query["fp"]
            map["client-fingerprint"] = if (fp.isNullOrEmpty()) "chrome" else fp
            query["alpn"]?.let { map["alpn"] = it.split(",") }
            query["pcs"]?.let { map["fingerprint"] = it }
        }
        query["sni"]?.let { map["servername"] = it }
        query["pbk"]?.let { pbk ->
            map["reality-opts"] = mapOf(
                "public-key" to pbk,
                "short-id" to (query["sid"] ?: ""),
            )
        }

        when (query["packetEncoding"]) {
            "none" -> {}
            "packet" -> map["packet-addr"] = true
            else -> map["xudp"] = true
        }

        var network = query["type"]?.lowercase() ?: "tcp"
        val fakeType = query["headerType"]?.lowercase() ?: ""
        if (fakeType == "http") {
            network = "http"
        } else if (network == "http") {
            network = "h2"
        }
        map["network"] = network

        when (network) {
            "tcp" -> {
                if (fakeType != "none" && fakeType.isNotEmpty()) {
                    val httpOpts = mutableMapOf<String, Any>()
                    httpOpts["path"] = listOf(query["path"] ?: "/")
                    query["host"]?.let { httpOpts["headers"] = mapOf("Host" to listOf(it)) }
                    query["method"]?.let { httpOpts["method"] = it }
                    map["http-opts"] = httpOpts
                }
            }

            "http" -> {
                val h2Opts = mutableMapOf<String, Any>()
                h2Opts["path"] = listOf(query["path"] ?: "/")
                query["host"]?.let { h2Opts["host"] = listOf(it) }
                map["h2-opts"] = h2Opts
            }

            "ws", "httpupgrade" -> {
                val wsOpts = mutableMapOf<String, Any>()
                val headers = mutableMapOf<String, Any>()
                query["host"]?.let { headers["Host"] = it }
                wsOpts["path"] = query["path"] ?: "/"
                if (headers.isNotEmpty()) wsOpts["headers"] = headers
                map["ws-opts"] = wsOpts
            }

            "grpc" -> {
                query["serviceName"]?.let {
                    map["grpc-opts"] = mapOf("grpc-service-name" to it)
                }
            }
        }
    }

    // === YAML 配置生成 ===

    private fun generateConfig(proxies: List<Map<String, Any>>): String = buildString {
        appendLine("mixed-port: 7890")
        appendLine("allow-lan: false")
        appendLine("mode: rule")
        appendLine("log-level: info")
        appendLine()

        appendLine("proxies:")
        for (proxy in proxies) {
            appendProxyEntry(proxy, indent = 2)
        }
        appendLine()

        appendLine("proxy-groups:")
        appendLine("  - name: PROXY")
        appendLine("    type: select")
        appendLine("    proxies:")
        for (proxy in proxies) {
            val name = proxy["name"]?.toString() ?: continue
            appendLine("      - ${yamlQuote(name)}")
        }
        appendLine()

        appendLine("rules:")
        appendLine("  - MATCH,PROXY")
    }

    private fun StringBuilder.appendProxyEntry(proxy: Map<String, Any>, indent: Int) {
        val prefix = " ".repeat(indent)
        var first = true
        for ((key, value) in proxy) {
            if (first) {
                append("$prefix- ")
                first = false
            } else {
                append("$prefix  ")
            }
            val childIndent = indent + 2
            appendYamlValue(key, value, childIndent)
        }
    }

    private fun StringBuilder.appendYamlValue(key: String, value: Any, baseIndent: Int) {
        when (value) {
            is Map<*, *> -> {
                appendLine("$key:")
                val childIndent = baseIndent + 2
                for ((k, v) in value) {
                    if (k != null && v != null) {
                        append(" ".repeat(childIndent))
                        appendYamlValue(k.toString(), v, childIndent)
                    }
                }
            }

            is List<*> -> {
                append("$key: [")
                append(value.filterNotNull().joinToString(", ") { yamlQuote(it.toString()) })
                appendLine("]")
            }

            is Boolean -> appendLine("$key: $value")
            is Number -> appendLine("$key: $value")
            is String -> appendLine("$key: ${yamlQuote(value)}")
            else -> appendLine("$key: ${yamlQuote(value.toString())}")
        }
    }

    // === 工具函数 ===

    private const val BASE64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=-_"

    private fun decodeBase64(input: String): String {
        val cleaned = input.trim().replace(Regex("[\\s\\r\\n]"), "")
        // 尝试多种 base64 编码
        val decoders = listOf(
            java.util.Base64.getDecoder(),
            java.util.Base64.getUrlDecoder(),
        )
        for (decoder in decoders) {
            try {
                // 补齐 padding
                val padded = when (cleaned.length % 4) {
                    2 -> cleaned + "=="
                    3 -> cleaned + "="
                    else -> cleaned
                }
                val bytes = decoder.decode(padded)
                val result = String(bytes, Charsets.UTF_8)
                // 检查解码结果是否包含可打印字符
                if (result.any { it.isLetterOrDigit() || it == ':' || it == '/' }) {
                    return result
                }
            } catch (_: Exception) {
            }
        }
        // 解码失败返回原文（可能本身就是明文）
        return input
    }

    private fun decodeUrlSafe(input: String): String {
        if (input.isEmpty()) return ""
        val converted = input.replace('-', '+').replace('_', '/')
        return try {
            val padded = when (converted.length % 4) {
                2 -> converted + "=="
                3 -> converted + "="
                else -> converted
            }
            String(java.util.Base64.getDecoder().decode(padded), Charsets.UTF_8)
        } catch (_: Exception) {
            input
        }
    }

    private fun urlDecode(input: String): String = try {
        URLDecoder.decode(input, "UTF-8")
    } catch (_: Exception) {
        input
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        return rawQuery.split("&").mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) urlDecode(parts[0]) to urlDecode(parts[1]) else null
        }.toMap()
    }

    private fun uniqueName(names: MutableMap<String, Int>, name: String): String {
        val safeName = name.ifEmpty { "unnamed" }
        val index = names[safeName]
        return if (index != null) {
            val newIndex = index + 1
            names[safeName] = newIndex
            "$safeName-%02d".format(newIndex)
        } else {
            names[safeName] = 0
            safeName
        }
    }

    private fun yamlQuote(value: String): String {
        if (value.isEmpty()) return "\"\""
        val needsQuote = value.any { it in ":{}[],'\"#|>&*!?@`\n\r\t" } ||
            value.startsWith(" ") || value.endsWith(" ") ||
            value == "true" || value == "false" ||
            value == "null" || value.toDoubleOrNull() != null
        return if (needsQuote) {
            "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        } else {
            value
        }
    }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.content ?: ""
}
