package top.yukonga.mishka.data.repository

import kotlinx.serialization.json.Json
import top.yukonga.mishka.data.model.ConfigurationOverride
import top.yukonga.mishka.platform.ProfileFileManager

/**
 * 用户 override 设置的持久化存储，路径 `files/mihomo/override.user.json`。
 *
 * `encodeDefaults = false` + `explicitNulls = false` 保证 JSON 只包含显式设置的字段，
 * 未提及的字段 mihomo 端 `json.Decode` 不动 RawConfig 原值。
 */
class OverrideJsonStore(private val fileManager: ProfileFileManager) {

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun load(): ConfigurationOverride {
        val text = fileManager.readMihomoFile(FILE_NAME) ?: return ConfigurationOverride()
        if (text.isBlank()) return ConfigurationOverride()
        return runCatching { json.decodeFromString<ConfigurationOverride>(text) }
            .onFailure { e ->
                // 文件损坏/字段类型错误：提示调用方
                println("OverrideJsonStore: failed to parse $FILE_NAME, falling back to defaults: ${e.message}")
            }
            .getOrDefault(ConfigurationOverride())
    }

    fun save(override: ConfigurationOverride) {
        fileManager.writeMihomoFile(FILE_NAME, json.encodeToString(override))
    }

    companion object {
        const val FILE_NAME = "override.user.json"
    }
}
