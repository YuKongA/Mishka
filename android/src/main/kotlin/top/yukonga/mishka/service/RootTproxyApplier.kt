package top.yukonga.mishka.service

import android.util.Log
import top.yukonga.mishka.viewmodel.AppProxyMode

/**
 * ROOT TPROXY 模式的 netfilter 规则装配。
 *
 * 与 [RootTetherHijacker] 互斥（前者为仅热点 TPROXY，本类扩展到本机 + 热点全流量）：
 * - 本类接管 `mangle PREROUTING + OUTPUT` + `nat PREROUTING + OUTPUT`；
 * - mihomo 此模式下 `tun.enable=false`、`tproxy-port=7895`、`routing-mark=0xff`、`dns.listen=0.0.0.0:1053`；
 * - sing-tun 完全不初始化，流量全部靠 iptables fwmark + `ip route add local default dev lo` 透明劫持。
 *
 * 常量选值对齐 box_for_magisk / Surfing / box4magisk 三家 Magisk 代理模块的共识值，
 * 以及 Mishka 现有 [RootTetherHijacker] 的 fwmark/table/priority，避开 Android Netd 低 16 位 mark。
 *
 * 生命周期：`MishkaRootService.startProxy`（ROOT_TPROXY 分支）成功后 apply；
 * stop/restart/进程死亡三路径 teardown（都走 NonCancellable）。
 * 幂等：重复 apply 会先 teardown 残留再装配；teardown 每条命令 `|| true`，可从任意前置状态清理。
 */
object RootTproxyApplier {

    private const val TAG = "RootTproxyApplier"

    // ========== fwmark / route table / priority（与 RootTetherHijacker 共享） ==========
    internal const val MARK = 0x01000000
    internal const val MASK = 0x01000000
    internal const val TABLE = 2024
    internal const val PRIORITY = 7999

    // ========== mihomo 监听端口 ==========
    internal const val TPROXY_PORT = RootTetherHijacker.TPROXY_PORT  // 7895
    internal const val DNS_PORT = 1053

    // ========== mihomo 出站自绕方式 ==========
    // 历史曾想用 mihomo `routing-mark` + `-m mark -j RETURN` 做精确放行，但 Android Netd
    // 用 fwmark 低 16 位编码 netId（见 `ip rule` 里的 `fwmark 0x1006d/0x1ffff lookup wlan0`），
    // mihomo 带任何 SO_MARK 都会被当成不存在的 netId，命中 legacy_system 表（无默认路由）
    // → mihomo 出站全部 network unreachable。
    // 现方案：不设 SO_MARK，按 `-m owner --uid-owner 0 -j RETURN` 放行所有 root 进程。
    // mihomo 通过 su 以 uid=0 运行，因此精确覆盖；副作用是 adbd/系统 root 工具的出站也不经 TPROXY，
    // 但用户空间 app（uid 10000+）全部正常代理，符合 box_for_magisk 等模块的常见取舍。
    internal const val MIHOMO_BYPASS_UID = 0

    // ========== 自建 chain 名 ==========
    private const val CHAIN_PRE = "mishka_tproxy_pre"
    private const val CHAIN_OUT = "mishka_tproxy_out"
    private const val CHAIN_DIVERT = "mishka_tproxy_divert"
    private const val CHAIN_DNS_PRE = "mishka_dns_pre"
    private const val CHAIN_DNS_OUT = "mishka_dns_out"

    // ========== 局域网 / 保留网段（同三家模块，用于 RETURN） ==========
    private val INTRANET_V4 = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
        "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24",
        "192.88.99.0/24", "192.168.0.0/16", "198.51.100.0/24", "203.0.113.0/24",
        "224.0.0.0/4", "240.0.0.0/4", "255.255.255.255/32",
    )
    private val INTRANET_V6 = listOf(
        "::/128", "::1/128", "::ffff:0:0/96", "100::/64", "64:ff9b::/96",
        "2001::/32", "2001:10::/28", "2001:20::/28", "2001:db8::/32",
        "2002::/16", "fc00::/7", "fe80::/10", "ff00::/8",
    )

    /**
     * 探测 xt_TPROXY 支持。复用 [RootTetherHijacker.probeTproxySupport]。
     */
    fun probeTproxySupport(): Boolean = RootTetherHijacker.probeTproxySupport()

    /**
     * 装配全部 TPROXY 规则。
     *
     * @param appUid Mishka 自身 UID（兜底自绕，routing-mark 之外的防御深度）
     * @param selectedUids AppProxyMode 的已选 UID 集合（白/黑名单语义由 mode 决定）
     * @param mode 分应用代理模式
     * @param tetherIfaces 热点接口列表（复用 ROOT_TETHER_IFACES 存储项）
     */
    fun apply(
        appUid: Int,
        selectedUids: Set<Int>,
        mode: AppProxyMode,
        tetherIfaces: List<String>,
    ) {
        Log.i(TAG, "apply: appUid=$appUid mode=$mode selected=${selectedUids.size} ifaces=$tetherIfaces")
        val script = buildApplyScript(appUid, selectedUids, mode, tetherIfaces)
        val code = RootHelper.runRootScriptHeredoc(script, timeoutSeconds = 20)
        Log.i(TAG, "apply finished code=$code")
    }

    /**
     * 拆除全部 TPROXY 规则（严格逆序 + 每步 `|| true`，幂等）。
     */
    fun teardown() {
        Log.i(TAG, "teardown")
        val code = RootHelper.runRootScriptHeredoc(buildTeardownScript(), timeoutSeconds = 15)
        Log.i(TAG, "teardown finished code=$code")
    }

    // ========================================================================
    // 脚本生成
    // ========================================================================

    private fun buildApplyScript(
        appUid: Int,
        selectedUids: Set<Int>,
        mode: AppProxyMode,
        tetherIfaces: List<String>,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("#!/system/bin/sh")
        sb.appendLine("set +e")
        // 0. 幂等前置：清残留（可能来自旧版本 / 异常退出），失败全忽略
        sb.append(buildTeardownScript(includeShebang = false))
        sb.appendLine()
        sb.appendLine("# === 1. ip rule + route ===")
        sb.appendLine("ip rule  add fwmark $MARK/$MASK lookup $TABLE priority $PRIORITY 2>/dev/null")
        sb.appendLine("ip -6 rule add fwmark $MARK/$MASK lookup $TABLE priority $PRIORITY 2>/dev/null")
        sb.appendLine("ip  route add local default dev lo table $TABLE 2>/dev/null")
        sb.appendLine("ip -6 route add local default dev lo table $TABLE 2>/dev/null")

        sb.appendLine()
        sb.appendLine("# === 2. 自建 chain ===")
        for (t in TABLES) {
            sb.appendLine("$t -w -t mangle -N $CHAIN_PRE")
            sb.appendLine("$t -w -t mangle -N $CHAIN_OUT")
            sb.appendLine("$t -w -t mangle -N $CHAIN_DIVERT")
            sb.appendLine("$t -w -t nat -N $CHAIN_DNS_PRE 2>/dev/null")
            sb.appendLine("$t -w -t nat -N $CHAIN_DNS_OUT 2>/dev/null")
        }

        sb.appendLine()
        sb.appendLine("# === 3. DIVERT chain: ESTABLISHED 流快速通道 (MARK + ACCEPT) ===")
        for (t in TABLES) {
            sb.appendLine("$t -w -t mangle -A $CHAIN_DIVERT -j MARK --set-xmark $MARK/$MASK")
            sb.appendLine("$t -w -t mangle -A $CHAIN_DIVERT -j ACCEPT")
        }

        sb.appendLine()
        sb.appendLine("# === 4. PREROUTING 主链 (intranet RETURN + LOCAL RETURN + DIVERT + TPROXY) ===")
        appendIntranetReturns(sb, table = "mangle", chain = CHAIN_PRE)
        // 本机接口任一 IP 为 dst 的包：RETURN（mihomo API、系统服务、dns.listen 等本地监听都要放行，避免劫持）
        sb.appendLine("iptables  -w -t mangle -A $CHAIN_PRE -m addrtype --dst-type LOCAL -j RETURN")
        sb.appendLine("ip6tables -w -t mangle -A $CHAIN_PRE -m addrtype --dst-type LOCAL -j RETURN 2>/dev/null")
        // DIVERT：已有 mihomo accepted socket 的后续包（ESTABLISHED TCP 或复用 UDP socket）跳过 TPROXY，
        // 仅打 mark 让 fwmark 路由命中 table 2024 投递 socket；避免 TPROXY 重复拦截造成连接中断
        for (t in TABLES) {
            sb.appendLine("$t -w -t mangle -A $CHAIN_PRE -p tcp -m socket -j $CHAIN_DIVERT")
            sb.appendLine("$t -w -t mangle -A $CHAIN_PRE -p udp -m socket -j $CHAIN_DIVERT")
        }
        // lo TPROXY：OUTPUT 打 mark 后经 `local default dev lo` reinject，命中这里 attach mihomo socket
        for (proto in listOf("tcp", "udp")) {
            sb.appendLine("iptables  -w -t mangle -A $CHAIN_PRE -p $proto -i lo -j TPROXY --on-ip 127.0.0.1 --on-port $TPROXY_PORT --tproxy-mark $MARK/$MASK")
            sb.appendLine("ip6tables -w -t mangle -A $CHAIN_PRE -p $proto -i lo -j TPROXY --on-ip ::1 --on-port $TPROXY_PORT --tproxy-mark $MARK/$MASK 2>/dev/null")
        }
        // tether ifaces：热点客户端转发流量直接 TPROXY
        for (iface in tetherIfaces) {
            val esc = RootHelper.escapeShellSingleQuoted(iface)
            for (proto in listOf("tcp", "udp")) {
                sb.appendLine("iptables  -w -t mangle -A $CHAIN_PRE -p $proto -i $esc -j TPROXY --on-ip 127.0.0.1 --on-port $TPROXY_PORT --tproxy-mark $MARK/$MASK")
                sb.appendLine("ip6tables -w -t mangle -A $CHAIN_PRE -p $proto -i $esc -j TPROXY --on-ip ::1 --on-port $TPROXY_PORT --tproxy-mark $MARK/$MASK 2>/dev/null")
            }
        }

        sb.appendLine()
        sb.appendLine("# === 5. OUTPUT 主链 (本机按 AppProxyMode 打 mark) ===")
        for (t in TABLES) {
            // 5a. root 进程放行：mihomo 以 uid=0 (via su) 运行，必须排除否则死循环；
            //      顺带放行所有 root 工具（adbd / shell 等），是 box_for_magisk 系的常见取舍
            sb.appendLine("$t -w -t mangle -A $CHAIN_OUT -m owner --uid-owner $MIHOMO_BYPASS_UID -j RETURN")
            // 5b. Mishka 自身 app uid 兜底（非必要但多层防御）
            if (appUid != MIHOMO_BYPASS_UID) {
                sb.appendLine("$t -w -t mangle -A $CHAIN_OUT -m owner --uid-owner $appUid -j RETURN")
            }
            // 5c. 本机地址 / 广播 RETURN（curl localhost / mDNS / DHCP 等，不劫持）
            sb.appendLine("$t -w -t mangle -A $CHAIN_OUT -m addrtype --dst-type LOCAL -j RETURN")
            sb.appendLine("$t -w -t mangle -A $CHAIN_OUT -m addrtype --dst-type BROADCAST -j RETURN")
            // 5d. DNS 交由 nat chain 处理
            sb.appendLine("$t -w -t mangle -A $CHAIN_OUT -p udp --dport 53 -j RETURN")
            sb.appendLine("$t -w -t mangle -A $CHAIN_OUT -p tcp --dport 53 -j RETURN")
        }
        // 5d. 局域网 RETURN
        appendIntranetReturns(sb, table = "mangle", chain = CHAIN_OUT)

        // 5d. 按 AppProxyMode 打 mark
        sb.appendLine("# --- AppProxyMode: $mode ---")
        when (mode) {
            AppProxyMode.AllowAll -> {
                // 全局代理，appUid 上面已经 RETURN
                for (t in TABLES) {
                    for (proto in listOf("tcp", "udp")) {
                        sb.appendLine("$t -w -t mangle -A $CHAIN_OUT -p $proto -j MARK --set-xmark $MARK/$MASK")
                    }
                }
            }

            AppProxyMode.AllowSelected -> {
                // 仅白名单 UID 打 mark，其他默认 RETURN
                for (uid in selectedUids) {
                    if (uid == appUid) continue // 避免覆盖 5a 的自绕
                    for (t in TABLES) {
                        for (proto in listOf("tcp", "udp")) {
                            sb.appendLine("$t -w -t mangle -A $CHAIN_OUT -p $proto -m owner --uid-owner $uid -j MARK --set-xmark $MARK/$MASK")
                        }
                    }
                }
            }

            AppProxyMode.DenySelected -> {
                // 黑名单 UID RETURN，其余全代理
                for (uid in selectedUids) {
                    for (t in TABLES) {
                        sb.appendLine("$t -w -t mangle -A $CHAIN_OUT -m owner --uid-owner $uid -j RETURN")
                    }
                }
                for (t in TABLES) {
                    for (proto in listOf("tcp", "udp")) {
                        sb.appendLine("$t -w -t mangle -A $CHAIN_OUT -p $proto -j MARK --set-xmark $MARK/$MASK")
                    }
                }
            }
        }

        sb.appendLine()
        sb.appendLine("# === 6. DNS 劫持 (nat REDIRECT to mihomo dns.listen) ===")
        // PREROUTING 仅对 tether 转发流量生效；本机流量不经 PREROUTING nat，不会循环
        sb.appendLine("iptables  -w -t nat -A $CHAIN_DNS_PRE -p udp --dport 53 -j REDIRECT --to-ports $DNS_PORT")
        sb.appendLine("iptables  -w -t nat -A $CHAIN_DNS_PRE -p tcp --dport 53 -j REDIRECT --to-ports $DNS_PORT")
        sb.appendLine("ip6tables -w -t nat -A $CHAIN_DNS_PRE -p udp --dport 53 -j REDIRECT --to-ports $DNS_PORT 2>/dev/null")
        sb.appendLine("ip6tables -w -t nat -A $CHAIN_DNS_PRE -p tcp --dport 53 -j REDIRECT --to-ports $DNS_PORT 2>/dev/null")

        // OUTPUT 自绕顺序：root 进程（mihomo via su）优先 RETURN，mishka app uid 兜底；
        // 不放行会导致 mihomo 自己解析代理服务器域名时被重定向回自己 → 死循环 → 全网无法解析
        sb.appendLine("iptables  -w -t nat -A $CHAIN_DNS_OUT -m owner --uid-owner $MIHOMO_BYPASS_UID -j RETURN")
        if (appUid != MIHOMO_BYPASS_UID) {
            sb.appendLine("iptables  -w -t nat -A $CHAIN_DNS_OUT -m owner --uid-owner $appUid -j RETURN")
        }
        sb.appendLine("iptables  -w -t nat -A $CHAIN_DNS_OUT -p udp --dport 53 -j REDIRECT --to-ports $DNS_PORT")
        sb.appendLine("iptables  -w -t nat -A $CHAIN_DNS_OUT -p tcp --dport 53 -j REDIRECT --to-ports $DNS_PORT")
        sb.appendLine("ip6tables -w -t nat -A $CHAIN_DNS_OUT -m owner --uid-owner $MIHOMO_BYPASS_UID -j RETURN 2>/dev/null")
        if (appUid != MIHOMO_BYPASS_UID) {
            sb.appendLine("ip6tables -w -t nat -A $CHAIN_DNS_OUT -m owner --uid-owner $appUid -j RETURN 2>/dev/null")
        }
        sb.appendLine("ip6tables -w -t nat -A $CHAIN_DNS_OUT -p udp --dport 53 -j REDIRECT --to-ports $DNS_PORT 2>/dev/null")
        sb.appendLine("ip6tables -w -t nat -A $CHAIN_DNS_OUT -p tcp --dport 53 -j REDIRECT --to-ports $DNS_PORT 2>/dev/null")

        sb.appendLine()
        sb.appendLine("# === 7. 挂主链 ===")
        // DIVERT 已在 CHAIN_PRE 内部（intranet RETURN 之后、TPROXY 之前），外层只挂 CHAIN_PRE
        for (t in TABLES) {
            sb.appendLine("$t -w -t mangle -I PREROUTING -j $CHAIN_PRE")
            sb.appendLine("$t -w -t mangle -I OUTPUT -j $CHAIN_OUT")
        }
        sb.appendLine("iptables  -w -t nat -I PREROUTING -j $CHAIN_DNS_PRE")
        sb.appendLine("iptables  -w -t nat -I OUTPUT     -j $CHAIN_DNS_OUT")
        sb.appendLine("ip6tables -w -t nat -I PREROUTING -j $CHAIN_DNS_PRE 2>/dev/null")
        sb.appendLine("ip6tables -w -t nat -I OUTPUT     -j $CHAIN_DNS_OUT 2>/dev/null")

        sb.appendLine()
        sb.appendLine("exit 0")
        return sb.toString()
    }

    private fun buildTeardownScript(includeShebang: Boolean = true): String {
        val sb = StringBuilder()
        if (includeShebang) {
            sb.appendLine("#!/system/bin/sh")
            sb.appendLine("set +e")
        }
        sb.appendLine("# === 卸主链 (没挂过 -D 返回非零，忽略) ===")
        for (t in TABLES) {
            // 兼容旧版本：旧版在 PREROUTING 单独挂过 DIVERT，这里留一条兜底清理
            sb.appendLine("$t -w -t mangle -D PREROUTING -p tcp -m socket -j $CHAIN_DIVERT 2>/dev/null")
            sb.appendLine("$t -w -t mangle -D PREROUTING -j $CHAIN_PRE 2>/dev/null")
            sb.appendLine("$t -w -t mangle -D OUTPUT -j $CHAIN_OUT 2>/dev/null")
            sb.appendLine("$t -w -t nat -D PREROUTING -j $CHAIN_DNS_PRE 2>/dev/null")
            sb.appendLine("$t -w -t nat -D OUTPUT -j $CHAIN_DNS_OUT 2>/dev/null")
        }
        sb.appendLine()
        sb.appendLine("# === flush + delete 自建链 ===")
        for (t in TABLES) {
            for (c in listOf(CHAIN_DIVERT, CHAIN_PRE, CHAIN_OUT)) {
                sb.appendLine("$t -w -t mangle -F $c 2>/dev/null")
                sb.appendLine("$t -w -t mangle -X $c 2>/dev/null")
            }
            for (c in listOf(CHAIN_DNS_PRE, CHAIN_DNS_OUT)) {
                sb.appendLine("$t -w -t nat -F $c 2>/dev/null")
                sb.appendLine("$t -w -t nat -X $c 2>/dev/null")
            }
        }
        sb.appendLine()
        sb.appendLine("# === ip rule + route ===")
        sb.appendLine("i=0; while [ \$i -lt 32 ] && ip rule del priority $PRIORITY 2>/dev/null; do i=\$((i+1)); done")
        sb.appendLine("i=0; while [ \$i -lt 32 ] && ip -6 rule del priority $PRIORITY 2>/dev/null; do i=\$((i+1)); done")
        sb.appendLine("ip  route flush table $TABLE 2>/dev/null")
        sb.appendLine("ip -6 route flush table $TABLE 2>/dev/null")
        if (includeShebang) {
            sb.appendLine()
            sb.appendLine("exit 0")
        }
        return sb.toString()
    }

    /**
     * 往 [chain] 追加局域网/保留网段的 RETURN 规则：
     * 除 UDP/53 外，所有到 intranet 的流量跳出本链，让 DNS (udp/53) 继续走 nat REDIRECT 劫持。
     *
     * v4 严格（失败即 bug），v6 best-effort（部分 ROM v6 mangle 可能受限，失败静默）。
     */
    private fun appendIntranetReturns(sb: StringBuilder, table: String, chain: String) {
        for (net in INTRANET_V4) {
            sb.appendLine("iptables -w -t $table -A $chain -d $net -p udp ! --dport 53 -j RETURN")
            sb.appendLine("iptables -w -t $table -A $chain -d $net ! -p udp -j RETURN")
        }
        for (net in INTRANET_V6) {
            sb.appendLine("ip6tables -w -t $table -A $chain -d $net -p udp ! --dport 53 -j RETURN 2>/dev/null")
            sb.appendLine("ip6tables -w -t $table -A $chain -d $net ! -p udp -j RETURN 2>/dev/null")
        }
    }

    private val TABLES = listOf("iptables", "ip6tables")
}
