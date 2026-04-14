package top.yukonga.mishka.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Subscription(
    val id: String = "",
    val name: String = "",
    val url: String = "",
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,
    val updatedAt: Long = 0,
    val isActive: Boolean = false,
)
