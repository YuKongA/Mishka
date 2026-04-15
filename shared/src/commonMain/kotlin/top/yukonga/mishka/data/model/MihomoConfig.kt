package top.yukonga.mishka.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MihomoConfig(
    val port: Int = 0,
    @SerialName("socks-port") val socksPort: Int = 0,
    @SerialName("redir-port") val redirPort: Int = 0,
    @SerialName("tproxy-port") val tproxyPort: Int = 0,
    @SerialName("mixed-port") val mixedPort: Int = 0,
    @SerialName("allow-lan") val allowLan: Boolean = false,
    @SerialName("bind-address") val bindAddress: String = "",
    val mode: String = "rule",
    @SerialName("log-level") val logLevel: String = "info",
    val ipv6: Boolean = false,
    val tun: TunConfig? = null,
    @SerialName("unified-delay") val unifiedDelay: Boolean = false,
    @SerialName("geodata-mode") val geodataMode: Boolean = false,
    @SerialName("tcp-concurrent") val tcpConcurrent: Boolean = false,
    @SerialName("find-process-mode") val findProcessMode: String = "off",
    val sniffer: SnifferConfig? = null,
    val dns: DnsConfig? = null,
)

@Serializable
data class TunConfig(
    val enable: Boolean = false,
    val stack: String = "mixed",
    val device: String = "",
)

@Serializable
data class SnifferConfig(
    val enable: Boolean = false,
    @SerialName("force-dns-mapping") val forceDnsMapping: Boolean = false,
    @SerialName("parse-pure-ip") val parsePureIp: Boolean = false,
    @SerialName("override-destination") val overrideDestination: Boolean = false,
    @SerialName("force-domain") val forceDomain: List<String> = emptyList(),
    @SerialName("skip-domain") val skipDomain: List<String> = emptyList(),
)

@Serializable
data class DnsConfig(
    val enable: Boolean = false,
    val listen: String = "",
    val ipv6: Boolean = false,
    @SerialName("prefer-h3") val preferH3: Boolean = false,
    @SerialName("use-hosts") val useHosts: Boolean = false,
    @SerialName("enhanced-mode") val enhancedMode: String = "normal",
    val nameserver: List<String> = emptyList(),
    val fallback: List<String> = emptyList(),
    @SerialName("default-nameserver") val defaultNameserver: List<String> = emptyList(),
    @SerialName("fake-ip-filter") val fakeIpFilter: List<String> = emptyList(),
)

@Serializable
data class ConfigPatch(
    val port: Int? = null,
    @SerialName("socks-port") val socksPort: Int? = null,
    @SerialName("redir-port") val redirPort: Int? = null,
    @SerialName("tproxy-port") val tproxyPort: Int? = null,
    @SerialName("mixed-port") val mixedPort: Int? = null,
    val mode: String? = null,
    @SerialName("log-level") val logLevel: String? = null,
    @SerialName("allow-lan") val allowLan: Boolean? = null,
    @SerialName("bind-address") val bindAddress: String? = null,
    val ipv6: Boolean? = null,
    val tun: TunPatch? = null,
    @SerialName("unified-delay") val unifiedDelay: Boolean? = null,
    @SerialName("geodata-mode") val geodataMode: Boolean? = null,
    @SerialName("tcp-concurrent") val tcpConcurrent: Boolean? = null,
    @SerialName("find-process-mode") val findProcessMode: String? = null,
    val sniffer: SnifferPatch? = null,
    val dns: DnsPatch? = null,
)

@Serializable
data class TunPatch(
    val stack: String? = null,
)

@Serializable
data class SnifferPatch(
    val enable: Boolean? = null,
    @SerialName("force-dns-mapping") val forceDnsMapping: Boolean? = null,
    @SerialName("parse-pure-ip") val parsePureIp: Boolean? = null,
    @SerialName("override-destination") val overrideDestination: Boolean? = null,
    @SerialName("force-domain") val forceDomain: List<String>? = null,
    @SerialName("skip-domain") val skipDomain: List<String>? = null,
)

@Serializable
data class DnsPatch(
    val enable: Boolean? = null,
    val listen: String? = null,
    val ipv6: Boolean? = null,
    @SerialName("prefer-h3") val preferH3: Boolean? = null,
    @SerialName("use-hosts") val useHosts: Boolean? = null,
    @SerialName("enhanced-mode") val enhancedMode: String? = null,
    val nameserver: List<String>? = null,
    val fallback: List<String>? = null,
    @SerialName("default-nameserver") val defaultNameserver: List<String>? = null,
    @SerialName("fake-ip-filter") val fakeIpFilter: List<String>? = null,
)

@Serializable
data class MihomoVersion(
    val version: String = "",
    val meta: Boolean = true,
)
