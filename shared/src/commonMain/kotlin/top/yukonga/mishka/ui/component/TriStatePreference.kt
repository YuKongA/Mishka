package top.yukonga.mishka.ui.component

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

/**
 * 三态偏好组件，用于覆写设置中的三态 Boolean。
 * - null: 不修改（使用订阅配置原值）
 * - true: 启用
 * - false: 禁用
 */
@Composable
fun TriStatePreference(
    title: String,
    value: Boolean?,
    onValueChange: (Boolean?) -> Unit,
) {
    val items = listOf("不修改", "启用", "禁用")
    val selectedIndex = when (value) {
        null -> 0
        true -> 1
        false -> 2
    }

    OverlayDropdownPreference(
        title = title,
        summary = items[selectedIndex],
        items = items,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { index ->
            onValueChange(
                when (index) {
                    1 -> true
                    2 -> false
                    else -> null
                }
            )
        },
    )
}
