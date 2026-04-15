package top.yukonga.mishka.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Subscription(
    val id: String = "",
    val name: String = "",
    val type: String = "Url",
    val url: String = "",
    val interval: Long = 0,
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,
    val updatedAt: Long = 0,
    val isActive: Boolean = false,
    val imported: Boolean = false,
    val pending: Boolean = false,
)
