package top.yukonga.mishka.service

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * mihomo 运行时路径与 secret 工具。配置覆写不在此处生成，
 * 运行时合并逻辑见 [RuntimeOverrideBuilder]，用户持久设置见 OverrideJsonStore。
 */
object ConfigGenerator {

    fun generateSecret(): String = UUID.randomUUID().toString().take(16)

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
}
