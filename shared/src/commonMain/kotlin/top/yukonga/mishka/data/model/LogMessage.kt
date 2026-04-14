package top.yukonga.mishka.data.model

import kotlinx.serialization.Serializable

@Serializable
data class LogMessage(
    val type: String = "",
    val payload: String = "",
)
