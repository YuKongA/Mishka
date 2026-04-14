package top.yukonga.mishka.data.model

import kotlinx.serialization.Serializable

@Serializable
data class TrafficData(
    val up: Long = 0,
    val down: Long = 0,
    val upTotal: Long = 0,
    val downTotal: Long = 0,
)
