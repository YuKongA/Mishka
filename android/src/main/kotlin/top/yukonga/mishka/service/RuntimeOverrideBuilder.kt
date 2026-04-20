package top.yukonga.mishka.service

import android.content.Context
import kotlinx.serialization.json.Json
import top.yukonga.mishka.data.model.ConfigurationOverride
import top.yukonga.mishka.data.model.ProfileOverride
import top.yukonga.mishka.data.model.TunOverride
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.viewmodel.AppProxyMode
import java.io.File

/**
 * 运行时 override 组装：用户持久化的 ConfigurationOverride 叠加 TUN fd / rootMode / AppProxy
 * 等运行期字段，输出到 `files/mihomo/override.run.json`。
 *
 * mihomo 启动通过 `--override-json <path>` 参数读该文件，
 * 在 yaml.Unmarshal 之后、ParseRawConfig 之前 `json.NewDecoder().Decode(rawCfg)` 注入。
 */
object RuntimeOverrideBuilder {

    private const val FILE_NAME = "override.run.json"
    internal const val DEFAULT_TUN_DEVICE = "Mishka"

    // ROOT 模式 sing-tun 路由常量，与 RootTetherHijacker 对齐
    // sing-tun 默认值也是 2022 / 9000，此处显式注入避免上游默认值漂移
    internal const val ROOT_TUN_TABLE = 2022
    internal const val ROOT_TUN_RULE_INDEX = 9000

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    /**
     * 生成运行时 override 并写入 override.run.json，返回该文件绝对路径。
     *
     * secret / external-controller **不**写入 JSON —— 通过 mihomo `--secret` / `--ext-ctl`
     * CLI flag 传入（见 MihomoRunner）。profile 段固定禁用 store-selected（由 SelectionEntity 管理），
     * 启用 store-fake-ip（保留 fake-ip 缓存）。
     */
    fun buildAndWriteForRun(
        context: Context,
        userOverride: ConfigurationOverride,
        tunFd: Int,
        rootMode: Boolean,
        tproxyForTether: Boolean = false,
    ): File {
        val merged = userOverride.copy(
            externalController = null,
            secret = null,
            // ROOT + PROXY + xt_TPROXY 可用时强制开 mihomo tproxy 入站；
            // 其他场景保留用户 override 里的 tproxyPort（默认 null）
            tproxyPort = if (tproxyForTether) RootTetherHijacker.TPROXY_PORT else userOverride.tproxyPort,
            tun = buildTunOverride(context, rootMode, tunFd, userOverride.tun),
            profile = ProfileOverride(storeSelected = false, storeFakeIp = true),
        )
        val file = File(ConfigGenerator.getWorkDir(context), FILE_NAME)
        file.writeText(json.encodeToString(merged))
        return file
    }

    private fun buildTunOverride(
        context: Context,
        rootMode: Boolean,
        tunFd: Int,
        userTun: TunOverride?,
    ): TunOverride {
        val storage = PlatformStorage(context)

        // 分应用代理：仅 ROOT 模式通过 mihomo include/exclude-package 实现；
        // VPN 模式由 VpnService.Builder.addAllowed/DisallowedApplication 管，不走 mihomo
        // Mishka 自身保持排除，避免死循环
        val include: List<String>?
        val exclude: List<String>?
        if (rootMode) {
            val selfPkg = context.packageName
            val proxyMode = parseAppProxyMode(storage.getString(StorageKeys.APP_PROXY_MODE, AppProxyMode.AllowAll.name))
            val packages = storage.getStringSet(StorageKeys.APP_PROXY_PACKAGES, emptySet())
            when (proxyMode) {
                // 空列表时用无效包名占位，确保不代理任何应用
                AppProxyMode.AllowSelected -> {
                    val filtered = packages.filter { it != selfPkg }
                    include = if (filtered.isNotEmpty()) filtered else listOf("-")
                    exclude = null
                }

                AppProxyMode.DenySelected -> {
                    include = null
                    exclude = (packages + selfPkg).distinct()
                }

                AppProxyMode.AllowAll -> {
                    include = null
                    exclude = listOf(selfPkg)
                }
            }
        } else {
            include = null
            exclude = null
        }

        val inet6 = when {
            rootMode && storage.getString(StorageKeys.VPN_ALLOW_IPV6, "false") == "true" ->
                listOf("fdfe:dcba:9876::1/126")

            !rootMode -> emptyList()
            else -> null
        }

        val device = userTun?.device
            ?: if (rootMode) storage.getString(StorageKeys.ROOT_TUN_DEVICE, DEFAULT_TUN_DEVICE) else null

        return TunOverride(
            enable = true,
            device = device,
            stack = userTun?.stack,
            fileDescriptor = tunFd.takeIf { it >= 0 && !rootMode },
            autoRoute = rootMode,
            autoDetectInterface = rootMode,
            inet6Address = inet6,
            dnsHijack = listOf("0.0.0.0:53"),
            includePackage = include,
            excludePackage = exclude,
            iproute2TableIndex = if (rootMode) ROOT_TUN_TABLE else null,
            iproute2RuleIndex = if (rootMode) ROOT_TUN_RULE_INDEX else null,
        )
    }

    private fun parseAppProxyMode(name: String): AppProxyMode =
        runCatching { AppProxyMode.valueOf(name) }.getOrDefault(AppProxyMode.AllowAll)
}
