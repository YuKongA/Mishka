package top.yukonga.mishka.service

import android.content.Context
import top.yukonga.mishka.domain.model.ConfigurationOverride
import top.yukonga.mishka.domain.model.resolveSecretOrNull
import java.io.File
import java.util.UUID

/**
 * mihomo 运行时路径与 secret 工具。配置覆写不在此处生成，
 * 运行时合并逻辑见 [RuntimeOverrideBuilder]，用户持久设置见 OverrideJsonStore。
 */
object ConfigGenerator {

    fun generateSecret(): String = UUID.randomUUID().toString().take(16)

    /**
     * external-controller 的 secret，优先级：用户 override > 订阅 config.yaml 顶层 `secret:` >
     * 随机生成。两个 Service 都从这里取，别各写一份——它是「secret 优先级」这条约束的实现单点。
     */
    fun resolveSecret(
        context: Context,
        userOverride: ConfigurationOverride,
        subscriptionId: String?,
    ): String = userOverride.resolveSecretOrNull()
        ?: subscriptionId?.let { readSubscriptionSecret(context, it) }
        ?: generateSecret()

    fun getWorkDir(context: Context): File {
        val dir = File(context.filesDir, "mihomo")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 从订阅 config.yaml 中提取顶层 secret 字段。
     *
     * 仅做行级匹配（行首 `secret:`，不含缩进），支持单/双引号与行尾注释。
     *
     * @return 去引号后的 secret 值；订阅无该字段或文件不存在则返回 null
     */
    fun readSubscriptionSecret(context: Context, subscriptionId: String): String? {
        val file = ProfileFileOps.getSubscriptionConfigFile(context, subscriptionId)
        if (!file.exists()) return null
        val regex = Regex("""^secret:\s*(.+?)\s*(?:#.*)?$""")
        return try {
            file.useLines { lines ->
                for (line in lines) {
                    val raw = regex.matchEntire(line)?.groupValues?.get(1) ?: continue
                    val unquoted = when {
                        raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"') -> raw.substring(1, raw.length - 1)
                        raw.length >= 2 && raw.startsWith('\'') && raw.endsWith('\'') -> raw.substring(1, raw.length - 1)
                        else -> raw
                    }
                    return@useLines unquoted.takeIf { it.isNotEmpty() }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 从订阅 config.yaml 中提取顶层 `mixed-port` 字段，用于判断 mihomo 是否
     * 已经会监听 HTTP 代理端口（决定 [RuntimeOverrideBuilder] 是否注入兜底默认值）。
     *
     * 仅做行级匹配（行首 `mixed-port:`，不含缩进），支持行尾注释。值非正整数视为未设置。
     *
     * @return 解析到的端口（1..65535）；订阅无该字段、值非数字、或文件不存在则返回 null
     */
    fun readSubscriptionMixedPort(context: Context, subscriptionId: String): Int? {
        val file = ProfileFileOps.getSubscriptionConfigFile(context, subscriptionId)
        if (!file.exists()) return null
        val regex = Regex("""^mixed-port:\s*(\d+)\s*(?:#.*)?$""")
        return try {
            file.useLines { lines ->
                for (line in lines) {
                    val raw = regex.matchEntire(line)?.groupValues?.get(1) ?: continue
                    return@useLines raw.toIntOrNull()?.takeIf { it in 1..65535 }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
