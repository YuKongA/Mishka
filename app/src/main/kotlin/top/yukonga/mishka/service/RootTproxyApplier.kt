package top.yukonga.mishka.service

import android.util.Log
import top.yukonga.mishka.viewmodel.AppProxyMode

/**
 * ROOT TPROXY 模式的 netfilter 规则装配。
 *
 * 与 [RootTetherHijacker] 互斥（前者为仅热点 TPROXY，本类扩展到本机 + 热点全流量）：
 * - 本类只接管 `mangle PREROUTING + OUTPUT`；共享 DNS 由 [RootDnsApplier] 接管；
 * - mihomo 此模式下 `tun.enable=false`、`tproxy-port=7895`，不设置 SO_MARK；
 * - sing-tun 完全不初始化，流量全部靠 iptables fwmark + `ip route add local default dev lo` 透明劫持。
 *
 * 常量选值对齐 box_for_magisk / Surfing / box4magisk 三家 Magisk 代理模块的共识值，
 * 以及 Mishka 现有 [RootTetherHijacker] 的 fwmark/table/priority，避开 Android Netd 低 16 位 mark。
 *
 * 生命周期：`MishkaRootService.startProxy`（ROOT_TPROXY 分支）成功后 apply；
 * stop/restart/进程死亡三路径 teardown（都走 NonCancellable）。
 * 幂等：重复 apply 先清残留，任一必要命令失败在同一 su batch 回滚并抛异常。
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

    // ========== mihomo 出站自绕方式 ==========
    // **不用** mihomo 的 `routing-mark` + `-m mark -j RETURN` 做精确放行：Android Netd
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

    // 仅用于迁移清理旧版本 NAT DNS，不再安装这些链。
    private const val LEGACY_DNS_PRE = "mishka_dns_pre"
    private const val LEGACY_DNS_OUT = "mishka_dns_out"

    // 标签只标识自有规则，不能误删第三方代理模块的 hook。
    internal const val COMMENT_TAG_PREFIX = "mishka:tproxy:"

    /** 生成 `-m comment --comment "mishka:tproxy:<label>"` 片段；追加到 iptables 命令末尾。 */
    private fun tag(label: String): String = "-m comment --comment \"$COMMENT_TAG_PREFIX$label\""

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
     * @param ipv6Enabled 是否注入 ip6tables / `ip -6` 规则。复用 [StorageKeys.VPN_ALLOW_IPV6]：
     *   关闭（默认）时 IPv6 出站走内核原生主路由表，不被劫持到 mihomo；与 VPN/ROOT TUN
     *   `inet6-address` 控制粒度对齐，避免 mihomo `ipv6: false` 时 TPROXY 重定向 → 拨号
     *   失败 → App 重试紧密循环造成 mihomo 内存增长。
     */
    fun apply(
        appUid: Int,
        selectedUids: Set<Int>,
        mode: AppProxyMode,
        tetherIfaces: List<String>,
        ipv6Enabled: Boolean,
    ) {
        Log.i(TAG, "apply: appUid=$appUid mode=$mode selected=${selectedUids.size} ifaces=$tetherIfaces ipv6=$ipv6Enabled")
        val script = buildApplyScript(appUid, selectedUids, mode, tetherIfaces, ipv6Enabled)
        val code = RootHelper.runRootScriptHeredoc(script, timeoutSeconds = 120)
        check(code == 0) { "ROOT TPROXY installation failed (code=$code); rollback attempted" }
        Log.i(TAG, "apply finished")
    }

    /**
     * 拆除全部 TPROXY 规则（严格逆序 + 每步 `|| true`，幂等）。
     */
    fun teardown() {
        Log.i(TAG, "teardown")
        val code = RootHelper.runRootScriptHeredoc(buildTeardownScript(), timeoutSeconds = 120)
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
        ipv6Enabled: Boolean,
    ): String {
        // ipv6Enabled=false 时全程跳过 ip6tables / ip -6 命令的发射，保证 IPv6 出站走内核原生
        // 主路由表（设备有 native v6 时直连，无时 ENETUNREACH 让 App 快失败）
        val tables = if (ipv6Enabled) TABLES else V4_ONLY_TABLES
        val sb = StringBuilder()
        sb.appendLine("#!/system/bin/sh")
        sb.appendLine("tproxy_cleanup() {")
        sb.append(buildTeardownScript(includeShebang = false))
        sb.appendLine("}")
        sb.appendLine(
            """
            tproxy_rollback() {
                tproxy_status=${'$'}?
                trap - 0 HUP INT TERM
                if [ "${'$'}tproxy_status" -ne 0 ]; then
                    set +e
                    echo 'ROOT TPROXY install failed; rolling back' >&2
                    tproxy_cleanup
                fi
                exit "${'$'}tproxy_status"
            }
            trap tproxy_rollback 0
            trap 'exit 1' HUP INT TERM
            set -e
            tproxy_cleanup || true
            """.trimIndent()
        )
        sb.appendLine()
        sb.appendLine("# === 1. ip rule + route ===")
        sb.appendLine("ip rule add fwmark $MARK/$MASK lookup $TABLE priority $PRIORITY")
        sb.appendLine("ip route add local default dev lo table $TABLE")
        if (ipv6Enabled) {
            sb.appendLine("ip -6 rule add fwmark $MARK/$MASK lookup $TABLE priority $PRIORITY")
            sb.appendLine("ip -6 route add local default dev lo table $TABLE")
        }

        sb.appendLine()
        sb.appendLine("# === 2. 自建 chain ===")
        for (t in tables) {
            sb.appendLine("$t -t mangle -N $CHAIN_PRE")
            sb.appendLine("$t -t mangle -N $CHAIN_OUT")
            sb.appendLine("$t -t mangle -N $CHAIN_DIVERT")
        }

        sb.appendLine()
        sb.appendLine("# === 3. DIVERT chain: ESTABLISHED 流快速通道 (MARK + ACCEPT) ===")
        for (t in tables) {
            sb.appendLine("$t -t mangle -A $CHAIN_DIVERT -j MARK --set-xmark $MARK/$MASK ${tag("divert-mark")}")
            sb.appendLine("$t -t mangle -A $CHAIN_DIVERT -j ACCEPT ${tag("divert-accept")}")
        }

        sb.appendLine()
        sb.appendLine("# === 4. PREROUTING 主链 (intranet RETURN + LOCAL RETURN + DIVERT + TPROXY) ===")
        // 即使 TPROXY 重建后 hook 排在前面，也不能重新分类共享 DNS 标记。
        for (t in tables) {
            sb.appendLine("$t -t mangle -A $CHAIN_PRE -m mark --mark ${RootDnsApplier.MARK}/${RootDnsApplier.MASK} -j ACCEPT ${tag("pre-dns-accept")}")
        }
        appendIntranetReturns(sb, table = "mangle", chain = CHAIN_PRE, tagLabel = "pre-intranet", ipv6Enabled = ipv6Enabled)
        // 本机接口任一 IP 为 dst 的包：RETURN（mihomo API、系统服务、dns.listen 等本地监听都要放行，避免劫持）
        sb.appendLine("$IPT4 -t mangle -A $CHAIN_PRE -m addrtype --dst-type LOCAL -j RETURN ${tag("pre-local")} 2>/dev/null || true")
        if (ipv6Enabled) {
            sb.appendLine("$IPT6 -t mangle -A $CHAIN_PRE -m addrtype --dst-type LOCAL -j RETURN ${tag("pre-local-v6")} 2>/dev/null || true")
        }
        // DIVERT：已有 mihomo accepted socket 的后续包（ESTABLISHED TCP 或复用 UDP socket）跳过 TPROXY，
        // 仅打 mark 让 fwmark 路由命中 table 2024 投递 socket；避免 TPROXY 重复拦截造成连接中断
        for (t in tables) {
            sb.appendLine("$t -t mangle -A $CHAIN_PRE -p tcp -m socket -j $CHAIN_DIVERT ${tag("pre-divert-tcp")}")
            sb.appendLine("$t -t mangle -A $CHAIN_PRE -p udp -m socket -j $CHAIN_DIVERT ${tag("pre-divert-udp")}")
        }
        // lo TPROXY：OUTPUT 打 mark 后经 `local default dev lo` reinject，命中这里 attach mihomo socket
        for (proto in listOf("tcp", "udp")) {
            sb.appendLine(
                "$IPT4 -t mangle -A $CHAIN_PRE -p $proto -i lo -j TPROXY --on-ip 127.0.0.1 --on-port $TPROXY_PORT --tproxy-mark $MARK/$MASK ${
                    tag(
                        "pre-tproxy-lo-$proto"
                    )
                }"
            )
            if (ipv6Enabled) {
                sb.appendLine(
                    "$IPT6 -t mangle -A $CHAIN_PRE -p $proto -i lo -j TPROXY --on-ip ::1 --on-port $TPROXY_PORT --tproxy-mark $MARK/$MASK ${
                        tag(
                            "pre-tproxy-lo-$proto-v6"
                        )
                    }"
                )
            }
        }
        // tether ifaces：热点客户端转发流量直接 TPROXY
        for (iface in tetherIfaces) {
            val esc = RootHelper.escapeShellSingleQuoted(iface)
            for (proto in listOf("tcp", "udp")) {
                sb.appendLine(
                    "$IPT4 -t mangle -A $CHAIN_PRE -p $proto -i $esc -j TPROXY --on-ip 127.0.0.1 --on-port $TPROXY_PORT --tproxy-mark $MARK/$MASK ${
                        tag(
                            "pre-tproxy-tether-$proto"
                        )
                    }"
                )
                if (ipv6Enabled) {
                    sb.appendLine(
                        "$IPT6 -t mangle -A $CHAIN_PRE -p $proto -i $esc -j TPROXY --on-ip ::1 --on-port $TPROXY_PORT --tproxy-mark $MARK/$MASK ${
                            tag(
                                "pre-tproxy-tether-$proto-v6"
                            )
                        }"
                    )
                }
            }
        }

        sb.appendLine()
        sb.appendLine("# === 5. OUTPUT 主链 (本机按 AppProxyMode 打 mark) ===")
        for (t in tables) {
            // 5a. root 进程放行：mihomo 以 uid=0 (via su) 运行，必须排除否则死循环；
            //      顺带放行所有 root 工具（adbd / shell 等），是 box_for_magisk 系的常见取舍
            sb.appendLine("$t -t mangle -A $CHAIN_OUT -m owner --uid-owner $MIHOMO_BYPASS_UID -j RETURN ${tag("out-bypass-root")}")
            // 5b. Mishka 自身 app uid 兜底（非必要但多层防御）
            if (appUid != MIHOMO_BYPASS_UID) {
                sb.appendLine("$t -t mangle -A $CHAIN_OUT -m owner --uid-owner $appUid -j RETURN ${tag("out-bypass-self")}")
            }
            // 5c. 本机地址 / 广播 RETURN（curl localhost / mDNS / DHCP 等，不劫持）
            sb.appendLine("$t -t mangle -A $CHAIN_OUT -m addrtype --dst-type LOCAL -j RETURN ${tag("out-local")} 2>/dev/null || true")
            // IPv6 没有广播地址类型，ip6tables 会拒绝该匹配条件。
            if (t == IPT4) {
                sb.appendLine("$t -t mangle -A $CHAIN_OUT -m addrtype --dst-type BROADCAST -j RETURN ${tag("out-broadcast")} 2>/dev/null || true")
            }
            // DNS 控制面独立于分应用数据代理，由共享规则接管。
            sb.appendLine("$t -t mangle -A $CHAIN_OUT -p udp --dport 53 -j RETURN ${tag("out-dns-udp")}")
            sb.appendLine("$t -t mangle -A $CHAIN_OUT -p tcp --dport 53 -j RETURN ${tag("out-dns-tcp")}")
        }
        // 5d. 局域网 RETURN
        appendIntranetReturns(sb, table = "mangle", chain = CHAIN_OUT, tagLabel = "out-intranet", ipv6Enabled = ipv6Enabled)

        // 5d. 按 AppProxyMode 打 mark
        sb.appendLine("# --- AppProxyMode: $mode ---")
        when (mode) {
            AppProxyMode.AllowAll -> {
                // 全局代理，appUid 上面已经 RETURN
                for (t in tables) {
                    for (proto in listOf("tcp", "udp")) {
                        sb.appendLine("$t -t mangle -A $CHAIN_OUT -p $proto -j MARK --set-xmark $MARK/$MASK ${tag("out-mark-$proto")}")
                    }
                }
            }

            AppProxyMode.AllowSelected -> {
                // 仅白名单 UID 打 mark，其他默认 RETURN
                for (uid in selectedUids) {
                    if (uid == appUid) continue // 避免覆盖 5a 的自绕
                    for (t in tables) {
                        for (proto in listOf("tcp", "udp")) {
                            sb.appendLine(
                                "$t -t mangle -A $CHAIN_OUT -p $proto -m owner --uid-owner $uid -j MARK --set-xmark $MARK/$MASK ${
                                    tag(
                                        "out-mark-uid-$proto"
                                    )
                                }"
                            )
                        }
                    }
                }
            }

            AppProxyMode.DenySelected -> {
                // 黑名单 UID RETURN，其余全代理
                for (uid in selectedUids) {
                    for (t in tables) {
                        sb.appendLine("$t -t mangle -A $CHAIN_OUT -m owner --uid-owner $uid -j RETURN ${tag("out-deny-uid")}")
                    }
                }
                for (t in tables) {
                    for (proto in listOf("tcp", "udp")) {
                        sb.appendLine("$t -t mangle -A $CHAIN_OUT -p $proto -j MARK --set-xmark $MARK/$MASK ${tag("out-mark-$proto")}")
                    }
                }
            }
        }

        sb.appendLine()
        sb.appendLine("# === 6. 挂主链 ===")
        // DIVERT 已在 CHAIN_PRE 内部（intranet RETURN 之后、TPROXY 之前），外层只挂 CHAIN_PRE
        // 顶层 jump 不打 comment：teardown 的 `-D PREROUTING -j <chain>` 必须与 apply 时的
        // 规则逐参数精确匹配，一旦带上 comment，`-D` 也得原样带同一条 comment 才删得掉
        for (t in tables) {
            sb.appendLine("$t -t mangle -I PREROUTING -j $CHAIN_PRE")
            sb.appendLine("$t -t mangle -I OUTPUT -j $CHAIN_OUT")
        }

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
        sb.appendLine("# === 删除精确匹配的自有 hook，兼容重复安装的残留 ===")
        fun removeRule(t: String, table: String, parent: String, args: String) {
            sb.appendLine("tproxy_i=0; while [ \$tproxy_i -lt 32 ] && $t -t $table -C $parent $args 2>/dev/null; do")
            sb.appendLine("$t -t $table -D $parent $args 2>/dev/null || break")
            sb.appendLine("tproxy_i=\$((tproxy_i+1)); done")
        }
        for (t in TABLES) {
            // 仅清除直接挂在 PREROUTING 的自有 DIVERT hook。
            removeRule(t, "mangle", "PREROUTING", "-p tcp -m socket -j $CHAIN_DIVERT")
            removeRule(t, "mangle", "PREROUTING", "-j $CHAIN_PRE")
            removeRule(t, "mangle", "OUTPUT", "-j $CHAIN_OUT")
            removeRule(t, "nat", "PREROUTING", "-j $LEGACY_DNS_PRE")
            removeRule(t, "nat", "OUTPUT", "-j $LEGACY_DNS_OUT")
            // PRE 引用 DIVERT，必须先清空全部自有链再删除。
            for (c in listOf(CHAIN_DIVERT, CHAIN_PRE, CHAIN_OUT)) {
                sb.appendLine("$t -t mangle -F $c 2>/dev/null || true")
            }
            for (c in listOf(CHAIN_DIVERT, CHAIN_PRE, CHAIN_OUT)) {
                sb.appendLine("$t -t mangle -X $c 2>/dev/null || true")
            }
            for (c in listOf(LEGACY_DNS_PRE, LEGACY_DNS_OUT)) {
                sb.appendLine("$t -t nat -F $c 2>/dev/null || true")
                sb.appendLine("$t -t nat -X $c 2>/dev/null || true")
            }
        }
        sb.appendLine()
        sb.appendLine("# === ip rule + route ===")
        sb.appendLine("tproxy_i=0; while [ \$tproxy_i -lt 32 ] && ip rule del fwmark $MARK/$MASK lookup $TABLE priority $PRIORITY 2>/dev/null; do tproxy_i=\$((tproxy_i+1)); done")
        sb.appendLine("tproxy_i=0; while [ \$tproxy_i -lt 32 ] && ip -6 rule del fwmark $MARK/$MASK lookup $TABLE priority $PRIORITY 2>/dev/null; do tproxy_i=\$((tproxy_i+1)); done")
        sb.appendLine("ip route del local default dev lo table $TABLE 2>/dev/null || true")
        sb.appendLine("ip -6 route del local default dev lo table $TABLE 2>/dev/null || true")
        if (includeShebang) {
            sb.appendLine()
            sb.appendLine("exit 0")
        }
        return sb.toString()
    }

    /**
     * 往 [chain] 追加局域网/保留网段的 RETURN 规则：
     * 除 UDP/53 外，所有到 intranet 的流量跳出本链；DNS 已由共享 DNS 规则接管。
     *
     * v4/v6 都是必要规则，注入失败必须中止并回滚；
     * [ipv6Enabled]=false 时跳过 v6 行发射。
     */
    private fun appendIntranetReturns(
        sb: StringBuilder,
        table: String,
        chain: String,
        tagLabel: String,
        ipv6Enabled: Boolean,
    ) {
        for (net in IptablesIntranet.V4) {
            sb.appendLine("$IPT4 -t $table -A $chain -d $net -p udp ! --dport 53 -j RETURN ${tag(tagLabel)}")
            sb.appendLine("$IPT4 -t $table -A $chain -d $net ! -p udp -j RETURN ${tag(tagLabel)}")
        }
        if (ipv6Enabled) {
            for (net in IptablesIntranet.V6) {
                sb.appendLine("$IPT6 -t $table -A $chain -d $net -p udp ! --dport 53 -j RETURN ${tag("$tagLabel-v6")}")
                sb.appendLine("$IPT6 -t $table -A $chain -d $net ! -p udp -j RETURN ${tag("$tagLabel-v6")}")
            }
        }
    }

    // 秒数并进令牌，脚本里不再单独写 -w（理由见 RootTetherHijacker.IPT4）
    private val IPT4 = "iptables -w ${RootTetherHijacker.IPT_WAIT_SECONDS}"
    private val IPT6 = "ip6tables -w ${RootTetherHijacker.IPT_WAIT_SECONDS}"

    private val TABLES = listOf(IPT4, IPT6)
    private val V4_ONLY_TABLES = listOf(IPT4)
}
