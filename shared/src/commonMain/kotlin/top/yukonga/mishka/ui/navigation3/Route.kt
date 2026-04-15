package top.yukonga.mishka.ui.navigation3

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

sealed interface Route : NavKey {
    @Serializable
    data object Main : Route

    @Serializable
    data object Subscription : Route

    @Serializable
    data object SubscriptionAdd : Route

    @Serializable
    data object SubscriptionAddUrl : Route

    @Serializable
    data object Log : Route

    @Serializable
    data object Provider : Route

    @Serializable
    data object DnsQuery : Route

    @Serializable
    data object Connection : Route

    @Serializable
    data object NetworkSettings : Route

    @Serializable
    data object AppProxy : Route

    @Serializable
    data object MetaSettings : Route

    @Serializable
    data object About : Route
}
