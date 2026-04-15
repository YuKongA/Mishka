package top.yukonga.mishka.ui.component

import androidx.compose.runtime.Composable
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.common_disabled
import mishka.shared.generated.resources.common_enabled
import mishka.shared.generated.resources.common_not_modified
import org.jetbrains.compose.resources.stringResource
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
    val items = listOf(stringResource(Res.string.common_not_modified), stringResource(Res.string.common_enabled), stringResource(Res.string.common_disabled))
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
