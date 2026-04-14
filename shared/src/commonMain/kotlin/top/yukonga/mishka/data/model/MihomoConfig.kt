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
    val mode: String = "rule",
    @SerialName("log-level") val logLevel: String = "info",
    val ipv6: Boolean = false,
    val tun: TunConfig? = null,
)

@Serializable
data class TunConfig(
    val enable: Boolean = false,
    val stack: String = "mixed",
    val device: String = "",
)

@Serializable
data class ConfigPatch(
    val port: Int? = null,
    @SerialName("socks-port") val socksPort: Int? = null,
    @SerialName("mixed-port") val mixedPort: Int? = null,
    val mode: String? = null,
    @SerialName("log-level") val logLevel: String? = null,
    @SerialName("allow-lan") val allowLan: Boolean? = null,
    val ipv6: Boolean? = null,
    val tun: TunPatch? = null,
)

@Serializable
data class TunPatch(
    val stack: String? = null,
)

@Serializable
data class MihomoVersion(
    val version: String = "",
    val meta: Boolean = true,
)
