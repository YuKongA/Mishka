package top.yukonga.mishka.data.model

import kotlinx.serialization.Serializable

/**
 * 订阅类型枚举。Room 中通过 ProfileTypeConverter 转为 TEXT 列存储。
 */
@Serializable
enum class ProfileType {
    File, Url, External;

    companion object {
        fun fromStringOrDefault(value: String?, default: ProfileType = Url): ProfileType =
            runCatching { value?.let { valueOf(it) } }.getOrNull() ?: default
    }
}
