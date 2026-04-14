package top.yukonga.mishka.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ProxiesResponse(
    val proxies: Map<String, ProxyNode> = emptyMap(),
)

@Serializable
data class GroupsResponse(
    val proxies: List<ProxyNode> = emptyList(),
)

@Serializable
data class ProxyGroupDetail(
    val name: String = "",
    val type: String = "",
    val now: String = "",
    val all: List<ProxyNode> = emptyList(),
    val history: List<ProxyHistory> = emptyList(),
)
