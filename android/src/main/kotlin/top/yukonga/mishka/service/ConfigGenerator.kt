package top.yukonga.mishka.service

import android.content.Context
import android.util.Log
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.common.FlowStyle
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import top.yukonga.mishka.data.repository.OverrideStorageHelper
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.viewmodel.AppProxyMode
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

data class RunConfigResult(
    val configFile: File,
    val externalController: String,
    val secret: String,
)

/** 构建结果。纯数据，不包含文件写入副作用 —— 由调用方决定写入位置。 */
private data class BuildResult(
    val yamlString: String,
    val externalController: String,
    val secret: String,
)

object ConfigGenerator {

    private const val TAG = "ConfigGenerator"
    private const val DEFAULT_CONTROLLER = "127.0.0.1:9090"
    internal const val DEFAULT_TUN_DEVICE = "Mishka"

    /** mihomo -t 校验时使用的临时配置文件名（相对订阅 workDir） */
    const val VALIDATION_CONFIG_NAME = "config.validate.yaml"

    fun generateSecret(): String = UUID.randomUUID().toString().take(16)

    fun getWorkDir(context: Context): File {
        val dir = File(context.filesDir, "mihomo")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getConfigFile(context: Context): File = File(getWorkDir(context), "config.yaml")

    /**
     * 生成最终运行配置并写入全局 config.yaml。
     * 解析订阅 YAML 为 Map，移除 Mishka 管理的 key 后注入新值，最后序列化回 YAML。
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
    ): RunConfigResult {
        val result = buildRunConfig(context, secret, subscriptionId, tunFd, rootMode, stubTunForValidation = false)
        val configFile = getConfigFile(context)
        configFile.writeText(result.yamlString)
        return RunConfigResult(configFile, result.externalController, result.secret)
    }

    /**
     * 生成 mihomo -t 校验专用的配置并写入订阅目录。
     *
     * 与 writeRunConfig 共享 override 合并逻辑，确保校验的 YAML 与将要运行的 YAML 基本一致，
     * 避免"原始订阅通过校验但 override 合并后导致运行失败"的 silent gap。
     *
     * 唯一差异：tun 段注入 `{enable: false}` 占位，避免 mihomo -t 对 TUN 特定字段做强校验
     * （校验阶段没有 TUN fd，也不需要校验 auto-route 等运行时行为）。
     */
    fun writeValidationConfig(context: Context, subscriptionId: String): File {
        val result = buildRunConfig(
            context,
            secret = "validate",
            subscriptionId = subscriptionId,
            tunFd = -1,
            rootMode = false,
            stubTunForValidation = true,
        )
        val file = File(ProfileFileOps.getSubscriptionDir(context, subscriptionId), VALIDATION_CONFIG_NAME)
        file.writeText(result.yamlString)
        return file
    }

    private fun buildRunConfig(
        context: Context,
        secret: String,
        subscriptionId: String?,
        tunFd: Int,
        rootMode: Boolean,
        stubTunForValidation: Boolean,
    ): BuildResult {
        val storage = PlatformStorage(context)
        val h = OverrideStorageHelper

        val baseConfigText = subscriptionId?.let {
            val subConfig = ProfileFileOps.getSubscriptionConfigFile(context, it)
            try {
                subConfig.readText()
            } catch (_: FileNotFoundException) {
                ""
            }
        } ?: ""

        val root: MutableMap<String, Any?> = loadRootMap(baseConfigText)

        // snakeyaml-engine 默认 JsonSchema（YAML 1.2 严格 JSON 兼容），与 mihomo 的 Go yaml.v3 对齐：
        // `secret: 0020` 按 `^-?(0|[1-9][0-9]*)$` 不匹配 Int → 保留为 String "0020"，
        // 不再需要对 secret/external-controller 做原文级正则提取。
        val externalControllerOverride = h.readNullableString(storage, h.KEY_EXTERNAL_CONTROLLER)?.trim()
        val configExternalController = root["external-controller"]?.toString()?.trim()
        val configController = externalControllerOverride ?: configExternalController ?: DEFAULT_CONTROLLER
        // 0.0.0.0 是监听地址，作为客户端连接地址需替换为 127.0.0.1
        val effectiveController = configController.replace("0.0.0.0", "127.0.0.1")

        val configSecret = root["secret"]?.toString()
        val effectiveSecret = configSecret ?: secret

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

        root.remove("external-controller")
        root.remove("secret")
        root.remove("tun")
        if (httpPort != null) root.remove("port")
        if (socksPort != null) root.remove("socks-port")
        if (redirPort != null) root.remove("redir-port")
        if (tproxyPort != null) root.remove("tproxy-port")
        if (mixedPort != null) root.remove("mixed-port")
        if (allowLan != null) root.remove("allow-lan")
        if (ipv6 != null) root.remove("ipv6")
        if (bindAddress != null) root.remove("bind-address")
        if (logLevel != null) root.remove("log-level")
        if (unifiedDelay != null) root.remove("unified-delay")
        if (geodataMode != null) root.remove("geodata-mode")
        if (tcpConcurrent != null) root.remove("tcp-concurrent")
        if (findProcessMode != null) root.remove("find-process-mode")
        if (hasDnsOverrides) root.remove("dns")
        if (hasSnifferOverrides) root.remove("sniffer")

        root["external-controller"] = configController
        root["secret"] = effectiveSecret
        root["tun"] = if (stubTunForValidation) {
            // 校验阶段不需要 TUN fd 和 auto-route，用 enable:false 占位，
            // 避免 mihomo -t 对 TUN 特定字段做强校验
            linkedMapOf<String, Any?>("enable" to false)
        } else {
            buildTunMap(storage, rootMode, tunFd)
        }

        root.putIfNotNull("port", httpPort)
        root.putIfNotNull("socks-port", socksPort)
        root.putIfNotNull("redir-port", redirPort)
        root.putIfNotNull("tproxy-port", tproxyPort)
        root.putIfNotNull("mixed-port", mixedPort)
        root.putIfNotNull("allow-lan", allowLan)
        root.putIfNotNull("ipv6", ipv6)
        root.putIfNotNull("bind-address", bindAddress)
        root.putIfNotNull("log-level", logLevel)
        root.putIfNotNull("unified-delay", unifiedDelay)
        root.putIfNotNull("geodata-mode", geodataMode)
        root.putIfNotNull("tcp-concurrent", tcpConcurrent)
        root.putIfNotNull("find-process-mode", findProcessMode)

        if (hasDnsOverrides) {
            root["dns"] = linkedMapOf<String, Any?>().apply {
                putIfNotNull("enable", dnsEnable)
                putIfNotNull("listen", dnsListen)
                putIfNotNull("ipv6", dnsIpv6)
                putIfNotNull("prefer-h3", dnsPreferH3)
                putIfNotNull("use-hosts", dnsUseHosts)
                putIfNotNull("enhanced-mode", dnsEnhancedMode)
                putIfNotNull("nameserver", dnsNameservers)
                putIfNotNull("fallback", dnsFallback)
                putIfNotNull("default-nameserver", dnsDefaultNameserver)
                putIfNotNull("fake-ip-filter", dnsFakeIpFilter)
            }
        } else if (!root.containsKey("dns")) {
            root["dns"] = linkedMapOf<String, Any?>(
                "enable" to true,
                "listen" to "0.0.0.0:1053",
                "default-nameserver" to listOf("223.5.5.5", "119.29.29.29"),
                "nameserver" to listOf("https://doh.pub/dns-query", "https://dns.alidns.com/dns-query"),
            )
        }

        if (hasSnifferOverrides) {
            root["sniffer"] = linkedMapOf<String, Any?>().apply {
                putIfNotNull("enable", snifferEnable)
                putIfNotNull("force-dns-mapping", snifferForceDnsMapping)
                putIfNotNull("parse-pure-ip", snifferParsePureIp)
                putIfNotNull("override-destination", snifferOverrideDest)
                putIfNotNull("force-domain", snifferForceDomain)
                putIfNotNull("skip-domain", snifferSkipDomain)
            }
        }

        if (mixedPort == null && !root.containsKey("mixed-port")) {
            root["mixed-port"] = 7890
        }
        if (!root.containsKey("mode")) {
            root["mode"] = "rule"
        }

        return BuildResult(
            yamlString = Dump(dumpSettings).dumpToString(root),
            externalController = effectiveController,
            secret = effectiveSecret,
        )
    }

    private fun buildTunMap(
        storage: PlatformStorage,
        rootMode: Boolean,
        tunFd: Int,
    ): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        put("enable", true)
        if (rootMode) {
            // mihomo 自行创建 TUN + 管理路由 + 分应用代理
            put("device", storage.getString(StorageKeys.ROOT_TUN_DEVICE, DEFAULT_TUN_DEVICE))
            put("auto-route", true)
            put("auto-detect-interface", true)
            val proxyMode = parseAppProxyMode(storage.getString(StorageKeys.APP_PROXY_MODE, AppProxyMode.AllowAll.name))
            val packages = storage.getStringSet(StorageKeys.APP_PROXY_PACKAGES, emptySet())
            when (proxyMode) {
                // 空列表时用无效包名占位，确保不代理任何应用
                AppProxyMode.AllowSelected -> put("include-package", if (packages.isNotEmpty()) packages.toList() else listOf("-"))
                AppProxyMode.DenySelected -> if (packages.isNotEmpty()) put("exclude-package", packages.toList())
                AppProxyMode.AllowAll -> Unit
            }
        } else {
            if (tunFd >= 0) put("file-descriptor", tunFd)
            put("auto-route", false)
            put("auto-detect-interface", false)
        }
        put("stack", "mixed")
        put("inet4-address", listOf("198.18.0.1/30"))
        if (rootMode && storage.getString(StorageKeys.VPN_ALLOW_IPV6, "false") == "true") {
            put("inet6-address", listOf("fdfe:dcba:9876::1/126"))
        } else if (!rootMode) {
            put("inet6-address", emptyList<String>())
        }
        put("dns-hijack", listOf("0.0.0.0:53"))
    }

    private fun parseAppProxyMode(name: String): AppProxyMode =
        runCatching { AppProxyMode.valueOf(name) }.getOrDefault(AppProxyMode.AllowAll)

    private fun MutableMap<String, Any?>.putIfNotNull(key: String, value: Any?) {
        if (value != null) put(key, value)
    }

    // LoadSettings / DumpSettings 不可变，缓存复用；Load / Dump 实例带状态，按需构造
    private val loadSettings: LoadSettings = LoadSettings.builder()
        // 单个订阅配置上限 8MB（典型 <1MB，留 8x 余量；避免 32MB 上限带来 OOM 风险）
        .setCodePointLimit(8 * 1024 * 1024)
        .setAllowDuplicateKeys(true)
        // 对齐 Go yaml.v3（mihomo 所用）默认上限，覆盖重度使用 <<: *anchor 合并键的订阅
        .setMaxAliasesForCollections(1024)
        .build()

    private val dumpSettings: DumpSettings = DumpSettings.builder()
        .setDefaultFlowStyle(FlowStyle.BLOCK)
        .setIndent(2)
        // 不自动换行，保持长 URL / 正则等原样
        .setWidth(Int.MAX_VALUE)
        .setSplitLines(false)
        // 展开共享引用：避免生成的 config.yaml 出现 `*id001` 之类的匿名别名
        .setDereferenceAliases(true)
        .build()

    @Suppress("UNCHECKED_CAST")
    private fun loadRootMap(content: String): MutableMap<String, Any?> {
        if (content.isBlank()) return linkedMapOf()
        val loaded = try {
            Load(loadSettings).loadFromString(content)
        } catch (e: YamlEngineException) {
            // 即使 mihomo -t 已在导入时校验通过，snakeyaml-engine 仍可能因解析差异失败——
            // 必须写日志便于排查，否则 writeRunConfig 会生成只含 Mishka 注入段的空壳配置，
            // 启动后 UI 表现为"无代理组"
            Log.e(TAG, "Failed to parse subscription YAML, generating shell config only", e)
            null
        }
        val map = loaded as? Map<String, Any?> ?: return linkedMapOf()
        return LinkedHashMap(map)
    }
}
