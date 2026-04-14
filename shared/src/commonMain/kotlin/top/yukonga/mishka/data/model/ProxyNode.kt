package top.yukonga.mishka.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ProxyNode(
    val name: String = "",
    val type: String = "",
    val alive: Boolean = true,
    val now: String = "",
    val all: List<String> = emptyList(),
    val history: List<ProxyHistory> = emptyList(),
    val udp: Boolean = false,
    val xudp: Boolean = false,
    val tfo: Boolean = false,
)

@Serializable
data class ProxyHistory(
    val time: String = "",
    val delay: Int = 0,
)
