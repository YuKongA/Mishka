package top.yukonga.mishka.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import top.yukonga.mishka.data.model.ConfigurationOverride
import top.yukonga.mishka.platform.ProfileFileManager

/**
 * 用户 override 设置的持久化存储，路径 `files/mihomo/override.user.json`。
 *
 * `encodeDefaults = false` + `explicitNulls = false` 保证 JSON 只包含显式设置的字段，
 * 未提及的字段 mihomo 端 `json.Decode` 不动 RawConfig 原值。
 *
 * 同时维护一份内存 [state]，settings ViewModel 共享同一 store 实例时可订阅 StateFlow 实时同步；
 * 非订阅型消费者（HomeViewModel / 订阅模块 / Service）继续走 [load] 直读磁盘的语义。
 */
class OverrideJsonStore(private val fileManager: ProfileFileManager) {

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<ConfigurationOverride> = _state.asStateFlow()

    fun load(): ConfigurationOverride {
        val text = fileManager.readMihomoFile(FILE_NAME) ?: return ConfigurationOverride()
        if (text.isBlank()) return ConfigurationOverride()
        return runCatching { json.decodeFromString<ConfigurationOverride>(text) }
            .onFailure { e ->
                println("OverrideJsonStore: failed to parse $FILE_NAME, falling back to defaults: ${e.message}")
            }
            .getOrDefault(ConfigurationOverride())
    }

    fun save(override: ConfigurationOverride) {
        fileManager.writeMihomoFile(FILE_NAME, json.encodeToString(override))
        _state.value = override
    }

    /** 读盘 → transform → 写盘 + 更新 [state]，供 settings ViewModel 调用。 */
    fun update(transform: (ConfigurationOverride) -> ConfigurationOverride) {
        save(transform(load()))
    }

    companion object {
        const val FILE_NAME = "override.user.json"
    }
}
