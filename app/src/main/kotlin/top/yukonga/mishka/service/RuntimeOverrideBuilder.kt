package top.yukonga.mishka.service

import android.content.Context
import kotlinx.serialization.json.Json
import top.yukonga.mishka.domain.model.ConfigurationOverride
import top.yukonga.mishka.domain.model.DnsOverride
import top.yukonga.mishka.domain.model.ProfileOverride
import top.yukonga.mishka.domain.model.TunOverride
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.TunMode
import top.yukonga.mishka.service.RuntimeOverrideBuilder.DEFAULT_MIXED_PORT
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

    // VPN/ROOT TUN 共用 MTU。VpnService.Builder.setMtu 与 sing-tun cfg.Tun.MTU 必须同值：
    // sing-tun 在 fd 模式用 cfg.Tun.MTU 给 gvisor fdbased.New 设 endpoint 缓冲，0 时所有 read 失败。
    internal const val VPN_TUN_MTU = 9000

    // ROOT TUN 模式 sing-tun 路由常量，与 RootTetherHijacker 对齐
    // sing-tun 默认值也是 2022 / 9000，此处显式注入避免上游默认值漂移
    internal const val ROOT_TUN_TABLE = 2022
    internal const val ROOT_TUN_RULE_INDEX = 9000

    // 「通过代理更新订阅」开启且用户未显式配置 mixed-port 时的兜底默认值，
    // 确保 mihomo 一定监听 HTTP 代理端口，让 SubscriptionProxyResolver 稳定解析到
    internal const val DEFAULT_MIXED_PORT = 7890

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
     *
     * mixed-port 决策（决定 mihomo 是否监听 HTTP 代理端口，[SubscriptionProxyResolver] 据此走代理）：
     * 1. 用户 override 显式设置 → 用用户值（覆盖订阅 yaml）
     * 2. 订阅 yaml 自带 `mixed-port` → 不注入，mihomo 沿用订阅 yaml 原值
     * 3. [subscriptionUpdateViaProxy] 启用 → 注入 [DEFAULT_MIXED_PORT] 兜底，确保开关稳定生效
     * 4. 其余情况不注入。调用方需先读订阅 yaml 用 [ConfigGenerator.readSubscriptionMixedPort]
     *    传入 [subscriptionMixedPort]，避免兜底值覆盖订阅自带的非默认端口。
     */
    fun buildAndWriteForRun(
        context: Context,
        userOverride: ConfigurationOverride,
        tunFd: Int,
        tunMode: TunMode,
        subscriptionUpdateViaProxy: Boolean,
        subscriptionMixedPort: Int?,
        tproxyForTether: Boolean = false,
    ): File {
        val storage = PlatformStorage(context)
        val wifiRuntimeMode = storage.getString(StorageKeys.WIFI_POLICY_RUNTIME_MODE, "")
            .takeIf { it == "rule" || it == "global" || it == "direct" }
        val merged = userOverride.copy(
            externalController = null,
            secret = null,
            mode = wifiRuntimeMode ?: userOverride.mode,
            mixedPort = when {
                userOverride.mixedPort != null -> userOverride.mixedPort
                subscriptionMixedPort != null -> null
                subscriptionUpdateViaProxy -> DEFAULT_MIXED_PORT
                else -> null
            },
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
        // 原子写：mihomo 紧接着就以 --override-json 读它，半个 JSON 会让启动失败且难以定位
        val file = File(ConfigGenerator.getWorkDir(context), FILE_NAME)
        ProfileFileOps.writeAtomically(file, json.encodeToString(merged))
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

        val ipv6Enabled = storage.getString(StorageKeys.VPN_ALLOW_IPV6, "false") == "true"
        // null 会被序列化省略；关闭时必须用空列表清除内核默认的 IPv6 地址。
        val inet6 = when {
            isRootTun && ipv6Enabled -> listOf("fdfe:dcba:9876::1/126")
            !isRootTun -> emptyList()
            else -> emptyList()
        }

        // ROOT TUN 的 auto_route 缺省铺满 0.0.0.0/0，会把 LAN 单播 + 224/4 组播吸进 mihomo，
        // 破坏同 LAN 设备发现 / P2P 直连（如妙享桌面）。注入私网 + 组播 route-exclude 留在物理网卡，
        // 与 VPN / TPROXY 的 LAN 放行对齐；仅 ROOT TUN 需要，用户显式设置优先。
        val routeExclude: List<String>? = when {
            !isRootTun -> userTun?.routeExcludeAddress
            else -> userTun?.routeExcludeAddress ?: buildList {
                addAll(IptablesIntranet.V4)
                if (ipv6Enabled) addAll(IptablesIntranet.V6)
            }
        }

        val device = userTun?.device
            ?: if (isRootTun) storage.getString(StorageKeys.ROOT_TUN_DEVICE, DEFAULT_TUN_DEVICE) else null

        // sing-tun userspace TUN 性能：mtu=9000 + gso + gso-max-size=65535 让大包聚合，
        // 减少每包 read syscall；仅 ROOT TUN 注入 GSO（VPN fd 由 VpnService 创建无 vnet header）。
        // 用户 override 的同名字段优先级最高，允许极端 ROM 下手动回退。
        // VPN 模式 MTU 必须与 VpnService.Builder.setMtu 同步：sing-tun fd 模式给 gvisor fdbased.New
        // 用 cfg.Tun.MTU 设 endpoint 缓冲，0 时所有 read 失败 → VPN 表面"延迟正常但流量不通"。
        val jumbo = storage.getString(StorageKeys.ROOT_TUN_JUMBO_MTU, "true") == "true"
        val defaultMtu: Int = if (isRootTun && !jumbo) 1500 else VPN_TUN_MTU
        val rootTunGso: Boolean? = if (isRootTun) jumbo else null
        val rootTunGsoMax: Int? = if (isRootTun && jumbo) 65535 else null

        return TunOverride(
            enable = true,
            device = device,
            stack = userTun?.stack,
            fileDescriptor = tunFd.takeIf { it >= 0 && !isRootTun },
            autoRoute = isRootTun,
            autoDetectInterface = isRootTun,
            routeExcludeAddress = routeExclude,
            inet6Address = inet6,
            dnsHijack = listOf("0.0.0.0:53"),
            includePackage = include,
            excludePackage = exclude,
            iproute2TableIndex = if (isRootTun) ROOT_TUN_TABLE else null,
            iproute2RuleIndex = if (isRootTun) ROOT_TUN_RULE_INDEX else null,
            mtu = userTun?.mtu ?: defaultMtu,
            gso = userTun?.gso ?: rootTunGso,
            gsoMaxSize = userTun?.gsoMaxSize ?: rootTunGsoMax,
        )
    }

    private fun parseAppProxyMode(name: String): AppProxyMode =
        runCatching { AppProxyMode.valueOf(name) }.getOrDefault(AppProxyMode.AllowAll)
}
