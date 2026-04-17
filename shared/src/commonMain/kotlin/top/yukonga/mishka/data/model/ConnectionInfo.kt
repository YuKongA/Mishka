package top.yukonga.mishka.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class ConnectionsResponse(
    val downloadTotal: Long = 0,
    val uploadTotal: Long = 0,
    val connections: List<ConnectionInfo> = emptyList(),
    val memory: Long = 0,
)

@Immutable
@Serializable
data class ConnectionInfo(
    val id: String = "",
    val upload: Long = 0,
    val download: Long = 0,
    val start: String = "",
    val chains: List<String> = emptyList(),
    val rule: String = "",
    val rulePayload: String = "",
    val metadata: ConnectionMetadata = ConnectionMetadata(),
)

@Immutable
@Serializable
data class ConnectionMetadata(
    val network: String = "",
    val type: String = "",
    val sourceIP: String = "",
    val destinationIP: String = "",
    val sourcePort: String = "",
    val destinationPort: String = "",
    val host: String = "",
    val dnsMode: String = "",
    val processPath: String = "",
    val process: String = "",
)
