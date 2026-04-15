package top.yukonga.mishka.service

import android.content.Context
import top.yukonga.mishka.data.repository.OverrideStorageHelper
import top.yukonga.mishka.platform.PlatformStorage
import java.io.File
import java.util.UUID

object ConfigGenerator {

    fun generateSecret(): String = UUID.randomUUID().toString().take(16)

    fun getWorkDir(context: Context): File {
        val dir = File(context.filesDir, "mihomo")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getConfigFile(context: Context): File = File(getWorkDir(context), "config.yaml")

    // === 两阶段目录结构 ===

    fun getImportedDir(context: Context, uuid: String): File {
        val dir = File(getWorkDir(context), "imported/$uuid")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getPendingDir(context: Context, uuid: String): File {
        val dir = File(getWorkDir(context), "pending/$uuid")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getProcessingDir(context: Context): File {
        val dir = File(getWorkDir(context), "processing")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 获取订阅专属目录（已导入的），mihomo 的 -d 参数指向此处。
     */
    fun getSubscriptionDir(context: Context, subscriptionId: String): File =
        getImportedDir(context, subscriptionId)

    fun getSubscriptionConfigFile(context: Context, subscriptionId: String): File =
        File(getImportedDir(context, subscriptionId), "config.yaml")

    /**
     * 保存订阅配置到 imported 目录。
     */
    fun saveSubscriptionConfig(context: Context, subscriptionId: String, content: String): File {
        val dir = getImportedDir(context, subscriptionId)
        val file = File(dir, "config.yaml")
        file.writeText(content)
        return file
    }

    /**
     * 保存配置到 pending 目录。
     */
    fun savePendingConfig(context: Context, uuid: String, content: String): File {
        val dir = getPendingDir(context, uuid)
        val file = File(dir, "config.yaml")
        file.writeText(content)
        return file
    }

    /**
     * commit：将 pending 移动到 imported（通过 processing 沙箱中转）。
     */
    fun commitPendingToImported(context: Context, uuid: String) {
        val pending = getPendingDir(context, uuid)
        val imported = getImportedDir(context, uuid)
        if (pending.exists()) {
            imported.deleteRecursively()
            pending.copyRecursively(imported, overwrite = true)
            pending.deleteRecursively()
        }
    }

    /**
     * release：丢弃 pending 目录。
     */
    fun releasePending(context: Context, uuid: String) {
        val pending = File(getWorkDir(context), "pending/$uuid")
        if (pending.exists()) pending.deleteRecursively()
    }

    /**
     * delete：同时删除 imported 和 pending 目录。
     */
    fun deleteProfileDirs(context: Context, uuid: String) {
        val imported = File(getWorkDir(context), "imported/$uuid")
        val pending = File(getWorkDir(context), "pending/$uuid")
        if (imported.exists()) imported.deleteRecursively()
        if (pending.exists()) pending.deleteRecursively()
    }

    /**
     * clone：复制 imported 文件到新 pending 目录。
     */
    fun cloneImportedToPending(context: Context, sourceUuid: String, targetUuid: String) {
        val source = getImportedDir(context, sourceUuid)
        val target = getPendingDir(context, targetUuid)
        if (source.exists()) {
            source.copyRecursively(target, overwrite = true)
        }
    }

    /**
     * 迁移旧版 profiles/{id}/ 目录到 imported/{id}/。
     */
    fun migrateProfileDirs(context: Context) {
        val oldProfilesDir = File(getWorkDir(context), "profiles")
        if (!oldProfilesDir.exists()) return
        val importedBaseDir = File(getWorkDir(context), "imported")
        importedBaseDir.mkdirs()
        oldProfilesDir.listFiles()?.forEach { subDir ->
            if (subDir.isDirectory) {
                val target = File(importedBaseDir, subDir.name)
                if (!target.exists()) {
                    subDir.copyRecursively(target, overwrite = true)
                }
            }
        }
        oldProfilesDir.deleteRecursively()
    }

    /**
     * 生成最终运行配置。
     * 以订阅配置为基础，用行过滤移除冲突 key，再注入 Mishka 控制参数和覆写设置。
     *
     * @param tunFd VPN 的 TUN 文件描述符，注入到 tun.file-descriptor
     */
    fun writeRunConfig(
        context: Context,
        secret: String,
        subscriptionId: String? = null,
        tunFd: Int = -1,
    ): File {
        val configFile = getConfigFile(context)
        val storage = PlatformStorage(context)
        val h = OverrideStorageHelper

        val baseConfig = if (subscriptionId != null) {
            val subConfig = getSubscriptionConfigFile(context, subscriptionId)
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
            // 覆写的简单顶层 key
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
            // 覆写的复杂段
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

            // 注入 Mishka 控制参数
            appendLine("# === Mishka injected ===")
            appendLine("external-controller: 127.0.0.1:9090")
            appendLine("secret: \"$secret\"")

            // TUN 配置（始终由 Mishka 控制）
            appendLine()
            appendLine("tun:")
            appendLine("  enable: true")
            if (tunFd >= 0) {
                appendLine("  file-descriptor: $tunFd")
            }
            appendLine("  stack: mixed")
            appendLine("  inet4-address: 198.18.0.1/30")
            appendLine("  inet6-address: []")
            appendLine("  auto-route: false")
            appendLine("  auto-detect-interface: false")
            appendLine("  dns-hijack:")
            appendLine("    - 0.0.0.0:53")

            // === 覆写：简单顶层 key ===
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

            // === 覆写：DNS 段 ===
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
                // 如果没有覆写也没有订阅 DNS，注入默认 DNS
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

            // === 覆写：Sniffer 段 ===
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

            // 默认端口和模式（如果没有覆写也没有在订阅中）
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

    /**
     * 过滤顶层 key 及其所有子行（缩进行）。
     * 当遇到要移除的顶层 key 时，跳过该行及后续所有缩进子行，直到下一个顶层 key。
     */
    private fun filterTopLevelKeys(content: String, keysToRemove: Set<String>): String {
        val lines = content.lines()
        val result = mutableListOf<String>()
        var skipping = false

        for (line in lines) {
            val trimmed = line.trimStart()

            // 空行和注释
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                if (!skipping) result.add(line)
                continue
            }

            // 判断是否为顶层 key（不以空格/tab 开头）
            val isTopLevel = !line.startsWith(" ") && !line.startsWith("\t")

            if (isTopLevel) {
                val key = trimmed.substringBefore(":").trim()
                skipping = key in keysToRemove
                if (!skipping) result.add(line)
            } else {
                // 缩进行：如果当前不在跳过模式则保留
                if (!skipping) result.add(line)
            }
        }

        return result.joinToString("\n")
    }
}
