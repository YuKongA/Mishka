package top.yukonga.mishka.data.repository

import java.security.MessageDigest

/**
 * Provider 条目类型
 */
enum class ProviderType { PROXY, RULE }

/**
 * 从配置中解析出的 Provider 信息
 */
data class ProviderEntry(
    val name: String,
    val type: ProviderType,
    val url: String,
    val path: String,
)

/**
 * 配置校验异常
 */
class ConfigValidationException(message: String) : Exception(message)

/**
 * Provider 下载结果
 */
data class ProviderDownloadResult(
    val total: Int,
    val success: Int,
    val failures: List<String>,
)

/**
 * 订阅配置处理器：行级 YAML 解析、配置校验、Provider 预下载。
 * 不引入 YAML 解析库，与 ConfigGenerator 的行过滤风格一致。
 */
object ConfigProcessor {

    // 解析状态机
    private enum class ParseState { IDLE, IN_SECTION, IN_PROVIDER }

    /**
     * 校验配置内容是否包含 proxies 或 proxy-providers。
     * @throws ConfigValidationException 当配置无效时
     */
    fun validate(configContent: String) {
        val lines = configContent.lines()
        var hasProxies = false
        var hasProxyProviders = false

        for (line in lines) {
            if (line.startsWith("#") || line.isBlank()) continue
            val indent = line.length - line.trimStart().length
            if (indent > 0) continue

            val key = line.trimStart().substringBefore(":").trim()
            when (key) {
                "proxies" -> hasProxies = true
                "proxy-providers" -> hasProxyProviders = true
            }
        }

        if (!hasProxies && !hasProxyProviders) {
            throw ConfigValidationException("配置无效：缺少 proxies 或 proxy-providers")
        }
    }

    /**
     * 从配置 YAML 中解析 proxy-providers 和 rule-providers 段，
     * 提取每个 provider 的 name、url、path。
     * 若 provider 只有 url 无 path，则自动生成路径（与 mihomo 一致）。
     */
    fun parseProviders(configContent: String): List<ProviderEntry> {
        val lines = configContent.lines()
        val providers = mutableListOf<ProviderEntry>()

        var state = ParseState.IDLE
        var currentType = ProviderType.PROXY
        var currentName = ""
        var currentUrl = ""
        var currentPath = ""

        fun saveCurrent() {
            if (currentName.isNotEmpty() && currentUrl.isNotEmpty()) {
                // 无 path 时根据 url 哈希生成默认路径（参考 CMFA/mihomo）
                val resolvedPath = currentPath.ifEmpty {
                    val prefix = if (currentType == ProviderType.PROXY) "proxies" else "rules"
                    "$prefix/${md5Hash(currentUrl)}"
                }
                providers.add(
                    ProviderEntry(
                        name = currentName,
                        type = currentType,
                        url = currentUrl,
                        path = resolvedPath,
                    )
                )
            }
            currentName = ""
            currentUrl = ""
            currentPath = ""
        }

        for (line in lines) {
            if (line.isBlank()) continue

            val trimmed = line.trimStart()
            if (trimmed.startsWith("#")) continue

            val indent = line.length - trimmed.length

            when {
                indent == 0 -> {
                    if (state == ParseState.IN_PROVIDER) saveCurrent()
                    val key = trimmed.substringBefore(":").trim()
                    when (key) {
                        "proxy-providers" -> {
                            state = ParseState.IN_SECTION
                            currentType = ProviderType.PROXY
                        }
                        "rule-providers" -> {
                            state = ParseState.IN_SECTION
                            currentType = ProviderType.RULE
                        }
                        else -> {
                            state = ParseState.IDLE
                        }
                    }
                }

                (state == ParseState.IN_SECTION || state == ParseState.IN_PROVIDER) && indent == 2 -> {
                    if (state == ParseState.IN_PROVIDER) saveCurrent()
                    currentName = trimmed.substringBefore(":").trim()
                    state = ParseState.IN_PROVIDER
                }

                state == ParseState.IN_PROVIDER && indent >= 4 -> {
                    val key = trimmed.substringBefore(":").trim()
                    val value = trimmed.substringAfter(":").trim().removeSurrounding("\"").removeSurrounding("'")
                    when (key) {
                        "url" -> currentUrl = value
                        "path" -> currentPath = value
                    }
                }
            }
        }

        if (state == ParseState.IN_PROVIDER) saveCurrent()

        return providers
    }

    /**
     * 下载所有有 url 的 provider 资源到订阅目录。
     */
    suspend fun downloadProviders(
        providers: List<ProviderEntry>,
        subscriptionDir: String,
        downloader: suspend (url: String) -> ByteArray,
        onProgress: (current: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
    ): ProviderDownloadResult {
        val downloadable = providers.filter { it.url.isNotEmpty() }
        if (downloadable.isEmpty()) return ProviderDownloadResult(0, 0, emptyList())

        val failures = mutableListOf<String>()
        var successCount = 0

        for ((index, provider) in downloadable.withIndex()) {
            onProgress(index + 1, downloadable.size, provider.name)

            try {
                val bytes = downloader(provider.url)
                writeProviderFile(subscriptionDir, provider.path, bytes)
                successCount++
            } catch (e: Exception) {
                failures.add("${provider.name}: ${e.message}")
            }
        }

        return ProviderDownloadResult(
            total = downloadable.size,
            success = successCount,
            failures = failures,
        )
    }

    private fun writeProviderFile(subscriptionDir: String, relativePath: String, content: ByteArray) {
        val cleanPath = relativePath.removePrefix("./").removePrefix("/")
        val fullPath = subscriptionDir.trimEnd('/', '\\') + "/" + cleanPath

        val file = java.io.File(fullPath)
        file.parentFile?.mkdirs()
        file.writeBytes(content)
    }

    /**
     * 对 URL 计算 MD5 哈希（与 mihomo/CMFA 的 provider 路径生成逻辑一致）
     */
    private fun md5Hash(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
