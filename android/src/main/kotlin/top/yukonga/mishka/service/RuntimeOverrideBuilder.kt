package top.yukonga.mishka.service

import android.content.Context
import kotlinx.serialization.json.Json
import top.yukonga.mishka.data.model.ConfigurationOverride
import top.yukonga.mishka.data.model.DnsOverride
import top.yukonga.mishka.data.model.ProfileOverride
import top.yukonga.mishka.data.model.TunOverride
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.TunMode
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

    // ROOT TUN 模式 sing-tun 路由常量，与 RootTetherHijacker 对齐
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
     *
     * 按 [tunMode] 分支注入策略：
     * - [TunMode.Vpn]        VpnService + sing-tun fd，仅写 `tun.file-descriptor`
     * - [TunMode.RootTun]    sing-tun auto_route + include/exclude-package；可选为 tether 加 tproxy-port
     * - [TunMode.RootTproxy] 关闭 TUN，写 tproxy-port + dns.listen，AppProxy 交 iptables uid-owner
     */
    fun buildAndWriteForRun(
        context: Context,
        userOverride: ConfigurationOverride,
        tunFd: Int,
        tunMode: TunMode,
        tproxyForTether: Boolean = false,
    ): File {
        val merged = userOverride.copy(
            externalController = null,
            secret = null,
            tproxyPort = when (tunMode) {
                // RootTproxy：mihomo 主入站就是 tproxy，端口锁定
                TunMode.RootTproxy -> RootTproxyApplier.TPROXY_PORT
                // RootTun：xt_TPROXY 可用 + 用户选 PROXY tether 时开 tproxy 入站（RootTetherHijacker 用）
                TunMode.RootTun -> if (tproxyForTether) RootTetherHijacker.TPROXY_PORT else userOverride.tproxyPort
                // Vpn：透传用户 override
                TunMode.Vpn -> userOverride.tproxyPort
            },
            // RootTproxy 下**不**注入 routing-mark：Android Netd 用 fwmark 低 16 位编码 netId，
            // mihomo 若带 SO_MARK 会被解释为不存在的 netId，命中 legacy_system 表（无默认路由）
            // → mihomo 出站全部 `network unreachable`。iptables 改用 `-m owner --uid-owner 0`
            // 放行 mihomo（它以 root 运行），副作用是所有 root 进程都不经 TPROXY，但用户 app 正常代理
            routingMark = userOverride.routingMark,
            // tcp-concurrent：代理侧并发拨号，显著降低首包延迟，多客户端并发场景尤其有效；
            // find-process-mode=off：ROOT TUN 分应用已由 sing-tun include/exclude-package 的 uidrange
            // 处理，mihomo 运行期遍历 /proc 查进程纯属冗余；VPN 模式 AppProxy 走 VpnService 同理。
            // 用户显式设置优先：仅在未设置时注入默认值
            tcpConcurrent = userOverride.tcpConcurrent ?: true,
            findProcessMode = userOverride.findProcessMode ?: "off",
            dns = buildDnsOverride(tunMode, userOverride.dns),
            tun = buildTunOverride(context, tunMode, tunFd, userOverride.tun),
            profile = ProfileOverride(storeSelected = false, storeFakeIp = true),
        )
        val file = File(ConfigGenerator.getWorkDir(context), FILE_NAME)
        file.writeText(json.encodeToString(merged))
        return file
    }

    /**
     * RootTproxy 下把 mihomo DNS listener 强制监听在 `0.0.0.0:1053`，
     * iptables 的 `nat REDIRECT --to-ports 1053` 把系统 DNS 查询导到这里。
     * 保留用户的 `enhanced-mode`（fake-ip / redir-host 任选）和其他字段。
     */
    private fun buildDnsOverride(tunMode: TunMode, userDns: DnsOverride?): DnsOverride? {
        if (tunMode != TunMode.RootTproxy) return userDns
        val base = userDns ?: DnsOverride()
        return base.copy(
            enable = true,
            listen = "0.0.0.0:${RootTproxyApplier.DNS_PORT}",
        )
    }

    private fun buildTunOverride(
        context: Context,
        tunMode: TunMode,
        tunFd: Int,
        userTun: TunOverride?,
    ): TunOverride {
        // RootTproxy：TUN 完全关闭，sing-tun 不初始化
        if (tunMode == TunMode.RootTproxy) {
            return TunOverride(enable = false)
        }

        val storage = PlatformStorage(context)
        val isRootTun = tunMode == TunMode.RootTun

        // 分应用代理：仅 RootTun 通过 mihomo include/exclude-package 实现；
        // VPN 由 VpnService.Builder.addAllowed/DisallowedApplication 管；
        // RootTproxy 走 iptables uid-owner（本函数早已 return）
        // Mishka 自身保持排除，避免死循环
        val include: List<String>?
        val exclude: List<String>?
        if (isRootTun) {
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
            isRootTun && storage.getString(StorageKeys.VPN_ALLOW_IPV6, "false") == "true" ->
                listOf("fdfe:dcba:9876::1/126")

            !isRootTun -> emptyList()
            else -> null
        }

        val device = userTun?.device
            ?: if (isRootTun) storage.getString(StorageKeys.ROOT_TUN_DEVICE, DEFAULT_TUN_DEVICE) else null

        // sing-tun userspace TUN 性能：mtu=9000 + gso + gso-max-size=65535 让大包聚合
        // 减少每包 read syscall；仅 ROOT TUN 场景注入（VPN 的 MTU 由 VpnService.Builder 系统管）。
        // 用户 override 的同名字段优先级最高，允许极端 ROM 下手动回退。
        val jumbo = storage.getString(StorageKeys.ROOT_TUN_JUMBO_MTU, "true") == "true"
        val rootTunMtu: Int? = if (isRootTun) (if (jumbo) 9000 else 1500) else null
        val rootTunGso: Boolean? = if (isRootTun) jumbo else null
        val rootTunGsoMax: Int? = if (isRootTun && jumbo) 65535 else null

        return TunOverride(
            enable = true,
            device = device,
            stack = userTun?.stack,
            fileDescriptor = tunFd.takeIf { it >= 0 && !isRootTun },
            autoRoute = isRootTun,
            autoDetectInterface = isRootTun,
            inet6Address = inet6,
            dnsHijack = listOf("0.0.0.0:53"),
            includePackage = include,
            excludePackage = exclude,
            iproute2TableIndex = if (isRootTun) ROOT_TUN_TABLE else null,
            iproute2RuleIndex = if (isRootTun) ROOT_TUN_RULE_INDEX else null,
            mtu = userTun?.mtu ?: rootTunMtu,
            gso = userTun?.gso ?: rootTunGso,
            gsoMaxSize = userTun?.gsoMaxSize ?: rootTunGsoMax,
        )
    }

    private fun parseAppProxyMode(name: String): AppProxyMode =
        runCatching { AppProxyMode.valueOf(name) }.getOrDefault(AppProxyMode.AllowAll)
}
