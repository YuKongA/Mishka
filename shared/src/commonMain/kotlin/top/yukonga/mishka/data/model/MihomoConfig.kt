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
data class MihomoVersion(
    val version: String = "",
    val meta: Boolean = true,
)
