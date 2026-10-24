package top.yukonga.mishka.ui.navigation

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * 二级页路由。back stack 靠 `Json.encodeToString<Route>` 多态编码整条栈持久化，
 * 新增路由只要 `@Serializable` 就自动获得进程死亡恢复能力。
 *
 * **基类这行 `@Serializable` 不能删**：看起来与各子类的标注重复，删掉照样编译，
 * 但多态序列化器由基类注册，缺了它恢复 back stack 时运行时抛 SerializationException。
 */
@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Main : Route

    @Serializable
    data object SubscriptionAdd : Route

    @Serializable
    data class SubscriptionAddUrl(
        val initialUrl: String = "",
        val initialName: String = "",
        val initialIntervalMinutes: Long = 0,
    ) : Route

    @Serializable
    data object Log : Route

    @Serializable
    data object Provider : Route

    @Serializable
    data object DnsQuery : Route

    @Serializable
    data object Connection : Route

    @Serializable
    data object VpnSettings : Route

    @Serializable
    data object RootSettings : Route

    @Serializable
    data object NetworkSettings : Route

    @Serializable
    data object ExternalControl : Route

    @Serializable
    data object Automation : Route

    @Serializable
    data object FileManager : Route

    @Serializable
    data object BackupRestore : Route

    @Serializable
    data class FileManagerEditor(val uuid: String, val relativePath: String) : Route

    @Serializable
    data object AppProxy : Route

    @Serializable
    data object WifiPolicy : Route

    @Serializable
    data object ThemeSettings : Route

    @Serializable
    data object MetaSettings : Route

    @Serializable
    data class SubscriptionEdit(val uuid: String) : Route

    @Serializable
    data class SubscriptionOverrides(val uuid: String) : Route

    @Serializable
    data object OverrideList : Route

    @Serializable
    data class OverrideEdit(val id: String? = null) : Route

    @Serializable
    data class OverrideFileEditor(val id: String) : Route

    @Serializable
    data object About : Route
}
