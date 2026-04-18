package top.yukonga.mishka.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.yukonga.mishka.data.model.ConfigurationOverride
import top.yukonga.mishka.data.repository.OverrideJsonStore

/**
 * mihomo 配置覆写 ViewModel。
 *
 * 状态即 [ConfigurationOverride]：字段 null 表示"不覆写"（沿用订阅配置原值）。
 * [update] 接受一个 copy lambda，保存到 override.user.json 后刷新 UI 状态；
 * 修改在下次启动/重启代理服务时经 `--override-json` 传给 mihomo 生效。
 */
class OverrideSettingsViewModel(
    private val store: OverrideJsonStore,
) : ViewModel() {

    private val _state = MutableStateFlow(store.load())
    val state: StateFlow<ConfigurationOverride> = _state.asStateFlow()

    fun update(transform: (ConfigurationOverride) -> ConfigurationOverride) {
        val next = transform(_state.value)
        store.save(next)
        _state.value = next
    }
}
