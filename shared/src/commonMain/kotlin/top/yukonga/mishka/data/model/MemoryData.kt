package top.yukonga.mishka.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class MemoryData(
    val inuse: Long = 0,
    val oslimit: Long = 0,
)
