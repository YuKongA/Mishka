package top.yukonga.mishka.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class LogMessage(
    val type: String = "",
    val payload: String = "",
)
