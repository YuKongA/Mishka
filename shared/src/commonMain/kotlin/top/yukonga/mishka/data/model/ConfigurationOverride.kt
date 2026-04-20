package top.yukonga.mishka.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * mihomo 配置覆写模型。字段按 mihomo `RawConfig` json tag 命名，
 * 通过 `--override-json <path>` 参数传给 mihomo，在 yaml.Unmarshal 之后、
 * ParseRawConfig 之前经 `json.NewDecoder(...).Decode(rawCfg)` 注入。
 *
 * 序列化时 null 字段不应输出，mihomo decode 跳过未提及字段即保留 RawConfig 原值。
 */
@Serializable
data class ConfigurationOverride(
    @SerialName("port") var httpPort: Int? = null,
    @SerialName("socks-port") var socksPort: Int? = null,
    @SerialName("redir-port") var redirPort: Int? = null,
    @SerialName("tproxy-port") var tproxyPort: Int? = null,
    @SerialName("mixed-port") var mixedPort: Int? = null,
    @SerialName("routing-mark") var routingMark: Int? = null,
    @SerialName("allow-lan") var allowLan: Boolean? = null,
    @SerialName("ipv6") var ipv6: Boolean? = null,
    @SerialName("bind-address") var bindAddress: String? = null,
    @SerialName("log-level") var logLevel: String? = null,
    @SerialName("mode") var mode: String? = null,
    @SerialName("external-controller") var externalController: String? = null,
    @SerialName("secret") var secret: String? = null,
    @SerialName("unified-delay") var unifiedDelay: Boolean? = null,
    @SerialName("geodata-mode") var geodataMode: Boolean? = null,
    @SerialName("tcp-concurrent") var tcpConcurrent: Boolean? = null,
    @SerialName("find-process-mode") var findProcessMode: String? = null,
    @SerialName("dns") var dns: DnsOverride? = null,
    @SerialName("sniffer") var sniffer: SnifferOverride? = null,
    @SerialName("tun") var tun: TunOverride? = null,
    @SerialName("profile") var profile: ProfileOverride? = null,
)

@Serializable
data class DnsOverride(
    @SerialName("enable") var enable: Boolean? = null,
    @SerialName("listen") var listen: String? = null,
    @SerialName("ipv6") var ipv6: Boolean? = null,
    @SerialName("prefer-h3") var preferH3: Boolean? = null,
    @SerialName("use-hosts") var useHosts: Boolean? = null,
    @SerialName("enhanced-mode") var enhancedMode: String? = null,
    @SerialName("nameserver") var nameserver: List<String>? = null,
    @SerialName("fallback") var fallback: List<String>? = null,
    @SerialName("default-nameserver") var defaultNameserver: List<String>? = null,
    @SerialName("fake-ip-filter") var fakeIpFilter: List<String>? = null,
)

@Serializable
data class SnifferOverride(
    @SerialName("enable") var enable: Boolean? = null,
    @SerialName("force-dns-mapping") var forceDnsMapping: Boolean? = null,
    @SerialName("parse-pure-ip") var parsePureIp: Boolean? = null,
    @SerialName("override-destination") var overrideDestination: Boolean? = null,
    @SerialName("force-domain") var forceDomain: List<String>? = null,
    @SerialName("skip-domain") var skipDomain: List<String>? = null,
)

/**
 * RawTun 覆写。不含 `inet4-address`（mihomo config.go:278 字段被注释），
 * VPN 模式 TUN v4 地址由 VpnService 分配，ROOT 模式 mihomo 用默认值。
 */
@Serializable
data class TunOverride(
    @SerialName("enable") var enable: Boolean? = null,
    @SerialName("device") var device: String? = null,
    @SerialName("stack") var stack: String? = null,
    @SerialName("file-descriptor") var fileDescriptor: Int? = null,
    @SerialName("auto-route") var autoRoute: Boolean? = null,
    @SerialName("auto-detect-interface") var autoDetectInterface: Boolean? = null,
    @SerialName("inet6-address") var inet6Address: List<String>? = null,
    @SerialName("dns-hijack") var dnsHijack: List<String>? = null,
    @SerialName("include-package") var includePackage: List<String>? = null,
    @SerialName("exclude-package") var excludePackage: List<String>? = null,
    @SerialName("iproute2-table-index") var iproute2TableIndex: Int? = null,
    @SerialName("iproute2-rule-index") var iproute2RuleIndex: Int? = null,
    @SerialName("mtu") var mtu: Int? = null,
    @SerialName("gso") var gso: Boolean? = null,
    @SerialName("gso-max-size") var gsoMaxSize: Int? = null,
)

@Serializable
data class ProfileOverride(
    @SerialName("store-selected") var storeSelected: Boolean? = null,
    @SerialName("store-fake-ip") var storeFakeIp: Boolean? = null,
)

/**
 * 解析用户的 external-controller 设置：trim、空字符串视为未设置、默认 `127.0.0.1:9090`，
 * 并把监听地址 `0.0.0.0` 替换为客户端可连的 `127.0.0.1`。
 */
fun ConfigurationOverride.resolveExternalController(): String =
    (externalController?.trim()?.takeIf { it.isNotEmpty() } ?: "127.0.0.1:9090")
        .replace("0.0.0.0", "127.0.0.1")

/**
 * 解析用户的 secret：trim、空字符串视为未设置（让调用方 fallback 到 random 生成值）。
 */
fun ConfigurationOverride.resolveSecretOrNull(): String? =
    secret?.trim()?.takeIf { it.isNotEmpty() }
