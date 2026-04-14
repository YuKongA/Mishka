package top.yukonga.mishka.service

import android.content.Context
import java.io.File
import java.util.UUID

object ConfigGenerator {

    fun generateSecret(): String = UUID.randomUUID().toString().take(16)

    fun getWorkDir(context: Context): File {
        val dir = File(context.filesDir, "mihomo")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getConfigFile(context: Context): File = File(getWorkDir(context), "config.yaml")

    /**
     * 获取订阅专属目录，mihomo 的 -d 参数指向此处。
     * provider 缓存文件也会存在这里，切换订阅不会冲突。
     */
    fun getSubscriptionDir(context: Context, subscriptionId: String): File {
        val dir = File(getWorkDir(context), "profiles/$subscriptionId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getSubscriptionConfigFile(context: Context, subscriptionId: String): File =
        File(getSubscriptionDir(context, subscriptionId), "config.yaml")

    /**
     * 保存订阅配置原始内容到订阅专属目录。
     */
    fun saveSubscriptionConfig(context: Context, subscriptionId: String, content: String): File {
        val dir = getSubscriptionDir(context, subscriptionId)
        val file = File(dir, "config.yaml")
        file.writeText(content)
        return file
    }

    /**
     * 生成最终运行配置。
     * 以订阅配置为基础，用行过滤移除冲突 key，再注入 Mishka 控制参数。
     *
     * @param tunFd VPN 的 TUN 文件描述符，注入到 tun.file-descriptor
     */
    fun writeRunConfig(
        context: Context,
        secret: String,
        subscriptionId: String? = null,
        tunFd: Int = -1,
    ): File {
        val configFile = getConfigFile(context)

        val baseConfig = if (subscriptionId != null) {
            val subConfig = getSubscriptionConfigFile(context, subscriptionId)
            if (subConfig.exists()) subConfig.readText() else ""
        } else {
            ""
        }

        // 行过滤：移除会被 Mishka 注入覆盖的顶层 key
        val keysToRemove = setOf("external-controller", "secret", "external-ui", "tun")
        val filteredLines = if (baseConfig.isNotEmpty()) {
            filterTopLevelKeys(baseConfig, keysToRemove)
        } else {
            ""
        }

        val config = buildString {
            if (filteredLines.isNotEmpty()) {
                appendLine(filteredLines)
                appendLine()
            }

            // 注入 Mishka 控制参数
            appendLine("# === Mishka injected ===")
            appendLine("external-controller: 127.0.0.1:9090")
            appendLine("secret: \"$secret\"")

            // TUN 配置（始终由 Mishka 控制）
            appendLine()
            appendLine("tun:")
            appendLine("  enable: true")
            if (tunFd >= 0) {
                appendLine("  file-descriptor: $tunFd")
            }
            appendLine("  stack: mixed")
            appendLine("  inet4-address: 198.18.0.1/30")
            appendLine("  inet6-address: []")
            appendLine("  auto-route: false")
            appendLine("  auto-detect-interface: false")
            appendLine("  dns-hijack:")
            appendLine("    - 0.0.0.0:53")

            if (!baseConfig.contains("dns:")) {
                appendLine()
                appendLine("dns:")
                appendLine("  enable: true")
                appendLine("  listen: 0.0.0.0:1053")
                appendLine("  default-nameserver:")
                appendLine("    - 223.5.5.5")
                appendLine("    - 119.29.29.29")
                appendLine("  nameserver:")
                appendLine("    - https://doh.pub/dns-query")
                appendLine("    - https://dns.alidns.com/dns-query")
            }

            if (!baseConfig.contains("mixed-port:")) {
                appendLine()
                appendLine("mixed-port: 7890")
            }

            if (!baseConfig.contains("mode:")) {
                appendLine("mode: rule")
            }
        }

        configFile.writeText(config)
        return configFile
    }

    /**
     * 过滤顶层 key 及其所有子行（缩进行）。
     * 当遇到要移除的顶层 key 时，跳过该行及后续所有缩进子行，直到下一个顶层 key。
     */
    private fun filterTopLevelKeys(content: String, keysToRemove: Set<String>): String {
        val lines = content.lines()
        val result = mutableListOf<String>()
        var skipping = false

        for (line in lines) {
            val trimmed = line.trimStart()

            // 空行和注释
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                if (!skipping) result.add(line)
                continue
            }

            // 判断是否为顶层 key（不以空格/tab 开头）
            val isTopLevel = !line.startsWith(" ") && !line.startsWith("\t")

            if (isTopLevel) {
                val key = trimmed.substringBefore(":").trim()
                skipping = key in keysToRemove
                if (!skipping) result.add(line)
            } else {
                // 缩进行：如果当前不在跳过模式则保留
                if (!skipping) result.add(line)
            }
        }

        return result.joinToString("\n")
    }
}
