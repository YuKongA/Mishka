package top.yukonga.mishka.service

import android.content.Context
import top.yukonga.mishka.data.repository.OverrideStorageHelper
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import java.io.File
import java.util.UUID

/**
 * 运行配置生成器：生成 mihomo 最终运行配置。
 * 订阅文件操作已拆分到 ProfileFileOps。
 */
object ConfigGenerator {

    fun generateSecret(): String = UUID.randomUUID().toString().take(16)

    fun getWorkDir(context: Context): File {
        val dir = File(context.filesDir, "mihomo")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getConfigFile(context: Context): File = File(getWorkDir(context), "config.yaml")

    /**
     * 生成最终运行配置。
     * 以订阅配置为基础，用行过滤移除冲突 key，再注入 Mishka 控制参数和覆写设置。
     *
     * @param tunFd VPN 的 TUN 文件描述符，注入到 tun.file-descriptor（VPN 模式）
     * @param rootMode 是否为 ROOT 模式（mihomo 自行创建 TUN 和管理路由）
     */
    fun writeRunConfig(
        context: Context,
        secret: String,
        subscriptionId: String? = null,
        tunFd: Int = -1,
        rootMode: Boolean = false,
    ): File {
        val configFile = getConfigFile(context)
        val storage = PlatformStorage(context)
        val h = OverrideStorageHelper

        val baseConfig = if (subscriptionId != null) {
            val subConfig = ProfileFileOps.getSubscriptionConfigFile(context, subscriptionId)
            if (subConfig.exists()) subConfig.readText() else ""
        } else {
            ""
        }

        // 读取覆写设置
        val httpPort = h.readNullableInt(storage, h.KEY_HTTP_PORT)
        val socksPort = h.readNullableInt(storage, h.KEY_SOCKS_PORT)
        val redirPort = h.readNullableInt(storage, h.KEY_REDIR_PORT)
        val tproxyPort = h.readNullableInt(storage, h.KEY_TPROXY_PORT)
        val mixedPort = h.readNullableInt(storage, h.KEY_MIXED_PORT)
        val allowLan = h.readNullableBoolean(storage, h.KEY_ALLOW_LAN)
        val ipv6 = h.readNullableBoolean(storage, h.KEY_IPV6)
        val bindAddress = h.readNullableString(storage, h.KEY_BIND_ADDRESS)
        val logLevel = h.readNullableString(storage, h.KEY_LOG_LEVEL)

        val dnsEnable = h.readNullableBoolean(storage, h.KEY_DNS_ENABLE)
        val dnsListen = h.readNullableString(storage, h.KEY_DNS_LISTEN)
        val dnsIpv6 = h.readNullableBoolean(storage, h.KEY_DNS_IPV6)
        val dnsPreferH3 = h.readNullableBoolean(storage, h.KEY_DNS_PREFER_H3)
        val dnsUseHosts = h.readNullableBoolean(storage, h.KEY_DNS_USE_HOSTS)
        val dnsEnhancedMode = h.readNullableString(storage, h.KEY_DNS_ENHANCED_MODE)
        val dnsNameservers = h.readNullableStringList(storage, h.KEY_DNS_NAMESERVERS)
        val dnsFallback = h.readNullableStringList(storage, h.KEY_DNS_FALLBACK)
        val dnsDefaultNameserver = h.readNullableStringList(storage, h.KEY_DNS_DEFAULT_NAMESERVER)
        val dnsFakeIpFilter = h.readNullableStringList(storage, h.KEY_DNS_FAKEIP_FILTER)

        val unifiedDelay = h.readNullableBoolean(storage, h.KEY_UNIFIED_DELAY)
        val geodataMode = h.readNullableBoolean(storage, h.KEY_GEODATA_MODE)
        val tcpConcurrent = h.readNullableBoolean(storage, h.KEY_TCP_CONCURRENT)
        val findProcessMode = h.readNullableString(storage, h.KEY_FIND_PROCESS_MODE)

        val snifferEnable = h.readNullableBoolean(storage, h.KEY_SNIFFER_ENABLE)
        val snifferForceDnsMapping = h.readNullableBoolean(storage, h.KEY_SNIFFER_FORCE_DNS_MAPPING)
        val snifferParsePureIp = h.readNullableBoolean(storage, h.KEY_SNIFFER_PARSE_PURE_IP)
        val snifferOverrideDest = h.readNullableBoolean(storage, h.KEY_SNIFFER_OVERRIDE_DEST)
        val snifferForceDomain = h.readNullableStringList(storage, h.KEY_SNIFFER_FORCE_DOMAIN)
        val snifferSkipDomain = h.readNullableStringList(storage, h.KEY_SNIFFER_SKIP_DOMAIN)

        val hasDnsOverrides = dnsEnable != null || dnsListen != null || dnsIpv6 != null ||
            dnsPreferH3 != null || dnsUseHosts != null || dnsEnhancedMode != null ||
            dnsNameservers != null || dnsFallback != null || dnsDefaultNameserver != null ||
            dnsFakeIpFilter != null

        val hasSnifferOverrides = snifferEnable != null || snifferForceDnsMapping != null ||
            snifferParsePureIp != null || snifferOverrideDest != null ||
            snifferForceDomain != null || snifferSkipDomain != null

        // 行过滤：移除会被 Mishka 注入或覆写的顶层 key
        val keysToRemove = buildSet {
            add("external-controller")
            add("secret")
            add("external-ui")
            add("tun")
            if (httpPort != null) add("port")
            if (socksPort != null) add("socks-port")
            if (redirPort != null) add("redir-port")
            if (tproxyPort != null) add("tproxy-port")
            if (mixedPort != null) add("mixed-port")
            if (allowLan != null) add("allow-lan")
            if (ipv6 != null) add("ipv6")
            if (bindAddress != null) add("bind-address")
            if (logLevel != null) add("log-level")
            if (unifiedDelay != null) add("unified-delay")
            if (geodataMode != null) add("geodata-mode")
            if (tcpConcurrent != null) add("tcp-concurrent")
            if (findProcessMode != null) add("find-process-mode")
            if (hasDnsOverrides) add("dns")
            if (hasSnifferOverrides) add("sniffer")
        }

        val filteredLines = if (baseConfig.isNotEmpty()) {
            filterTopLevelKeys(baseConfig, keysToRemove)
        } else {
            ""
        }

        val config = buildString {
            if (filteredLines.isNotEmpty()) {
                appendLine(filteredLines)
                appendLine()
            }

            appendLine("# === Mishka injected ===")
            appendLine("external-controller: 127.0.0.1:9090")
            appendLine("secret: \"$secret\"")

            appendLine()
            appendLine("tun:")
            appendLine("  enable: true")
            if (rootMode) {
                // ROOT 模式：mihomo 自行创建 TUN 设备和管理路由表
                appendLine("  auto-route: true")
                appendLine("  auto-detect-interface: true")
                // 分应用代理：通过 mihomo 的 include/exclude-package 实现
                val proxyMode = storage.getString(StorageKeys.APP_PROXY_MODE, "AllowAll")
                val packages = storage.getStringSet(StorageKeys.APP_PROXY_PACKAGES, emptySet())
                if (proxyMode == "AllowSelected" && packages.isNotEmpty()) {
                    appendLine("  include-package:")
                    packages.forEach { appendLine("    - $it") }
                } else if (proxyMode == "DenySelected" && packages.isNotEmpty()) {
                    appendLine("  exclude-package:")
                    packages.forEach { appendLine("    - $it") }
                }
            } else {
                // VPN 模式：使用 VpnService 提供的 TUN fd
                if (tunFd >= 0) {
                    appendLine("  file-descriptor: $tunFd")
                }
                appendLine("  auto-route: false")
                appendLine("  auto-detect-interface: false")
            }
            appendLine("  stack: mixed")
            appendLine("  inet4-address:")
            appendLine("    - 198.18.0.1/30")
            if (rootMode) {
                val allowIpv6 = storage.getString(StorageKeys.VPN_ALLOW_IPV6, "false") == "true"
                if (allowIpv6) {
                    appendLine("  inet6-address:")
                    appendLine("    - fdfe:dcba:9876::1/126")
                }
            } else {
                appendLine("  inet6-address: []")
            }
            appendLine("  dns-hijack:")
            appendLine("    - 0.0.0.0:53")

            appendLine()
            appendLine("# === Override settings ===")
            httpPort?.let { appendLine("port: $it") }
            socksPort?.let { appendLine("socks-port: $it") }
            redirPort?.let { appendLine("redir-port: $it") }
            tproxyPort?.let { appendLine("tproxy-port: $it") }
            mixedPort?.let { appendLine("mixed-port: $it") }
            allowLan?.let { appendLine("allow-lan: $it") }
            ipv6?.let { appendLine("ipv6: $it") }
            bindAddress?.let { appendLine("bind-address: \"$it\"") }
            logLevel?.let { appendLine("log-level: $it") }
            unifiedDelay?.let { appendLine("unified-delay: $it") }
            geodataMode?.let { appendLine("geodata-mode: $it") }
            tcpConcurrent?.let { appendLine("tcp-concurrent: $it") }
            findProcessMode?.let { appendLine("find-process-mode: $it") }

            if (hasDnsOverrides) {
                appendLine()
                appendLine("dns:")
                dnsEnable?.let { appendLine("  enable: $it") }
                dnsListen?.let { appendLine("  listen: $it") }
                dnsIpv6?.let { appendLine("  ipv6: $it") }
                dnsPreferH3?.let { appendLine("  prefer-h3: $it") }
                dnsUseHosts?.let { appendLine("  use-hosts: $it") }
                dnsEnhancedMode?.let { appendLine("  enhanced-mode: $it") }
                dnsNameservers?.let { list ->
                    appendLine("  nameserver:")
                    list.forEach { appendLine("    - $it") }
                }
                dnsFallback?.let { list ->
                    appendLine("  fallback:")
                    list.forEach { appendLine("    - $it") }
                }
                dnsDefaultNameserver?.let { list ->
                    appendLine("  default-nameserver:")
                    list.forEach { appendLine("    - $it") }
                }
                dnsFakeIpFilter?.let { list ->
                    appendLine("  fake-ip-filter:")
                    list.forEach { appendLine("    - $it") }
                }
            } else if (!baseConfig.contains("dns:")) {
                appendLine()
                appendLine("dns:")
                appendLine("  enable: true")
                appendLine("  listen: 0.0.0.0:1053")
                appendLine("  default-nameserver:")
                appendLine("    - 223.5.5.5")
                appendLine("    - 119.29.29.29")
                appendLine("  nameserver:")
                appendLine("    - https://doh.pub/dns-query")
                appendLine("    - https://dns.alidns.com/dns-query")
            }

            if (hasSnifferOverrides) {
                appendLine()
                appendLine("sniffer:")
                snifferEnable?.let { appendLine("  enable: $it") }
                snifferForceDnsMapping?.let { appendLine("  force-dns-mapping: $it") }
                snifferParsePureIp?.let { appendLine("  parse-pure-ip: $it") }
                snifferOverrideDest?.let { appendLine("  override-destination: $it") }
                snifferForceDomain?.let { list ->
                    appendLine("  force-domain:")
                    list.forEach { appendLine("    - $it") }
                }
                snifferSkipDomain?.let { list ->
                    appendLine("  skip-domain:")
                    list.forEach { appendLine("    - $it") }
                }
            }

            if (mixedPort == null && !baseConfig.contains("mixed-port:")) {
                appendLine()
                appendLine("mixed-port: 7890")
            }

            if (!baseConfig.contains("mode:")) {
                appendLine("mode: rule")
            }
        }

        configFile.writeText(config)
        return configFile
    }

    private fun filterTopLevelKeys(content: String, keysToRemove: Set<String>): String {
        val lines = content.lines()
        val result = mutableListOf<String>()
        var skipping = false

        for (line in lines) {
            val trimmed = line.trimStart()

            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                if (!skipping) result.add(line)
                continue
            }

            val isTopLevel = !line.startsWith(" ") && !line.startsWith("\t")

            if (isTopLevel) {
                val key = trimmed.substringBefore(":").trim()
                skipping = key in keysToRemove
                if (!skipping) result.add(line)
            } else {
                if (!skipping) result.add(line)
            }
        }

        return result.joinToString("\n")
    }
}
