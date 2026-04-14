package top.yukonga.mishka.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ProvidersResponse(
    val providers: Map<String, ProviderInfo> = emptyMap(),
)

@Serializable
data class ProviderInfo(
    val name: String = "",
    val type: String = "",
    val vehicleType: String = "",
    val updatedAt: String = "",
    val subscriptionInfo: SubscriptionInfo? = null,
    val proxies: List<ProxyNode> = emptyList(),
)

@Serializable
data class RuleProvidersResponse(
    val providers: Map<String, RuleProviderInfo> = emptyMap(),
)

@Serializable
data class RuleProviderInfo(
    val name: String = "",
    val type: String = "",
    val behavior: String = "",
    val vehicleType: String = "",
    val updatedAt: String = "",
    val ruleCount: Int = 0,
)

@Serializable
data class SubscriptionInfo(
    val Upload: Long = 0,
    val Download: Long = 0,
    val Total: Long = 0,
    val Expire: Long = 0,
)
