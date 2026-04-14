package top.yukonga.mishka.data.model

import kotlinx.serialization.Serializable

@Serializable
data class DelayResult(
    val delay: Int = 0,
)
