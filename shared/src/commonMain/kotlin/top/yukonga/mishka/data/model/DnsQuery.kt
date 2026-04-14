package top.yukonga.mishka.data.model

import kotlinx.serialization.Serializable

@Serializable
data class DnsQueryResponse(
    val Status: Int = 0,
    val Answer: List<DnsAnswer> = emptyList(),
)

@Serializable
data class DnsAnswer(
    val name: String = "",
    val TTL: Int = 0,
    val data: String = "",
    val type: Int = 0,
)
