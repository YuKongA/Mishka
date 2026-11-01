package top.yukonga.mishka.service

import android.util.Log
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * DNS 控制面独立于 TUN/TPROXY 数据面及其 IPv6 开关。
 *
 * 原生 listener 只绑定 loopback 的独立端口；本机 DNS 在 nat OUTPUT 被 DNAT 到该端口，
 * 53 留给 Android tethering 的 dnsmasq。协程取消与规则生命周期由 Service 管理。
 */
internal object RootDnsApplier {
    private const val TAG = "RootDnsApplier"
    const val DNS_PORT = 53
    private const val FALLBACK_PORT = 15353
    private val PORT_CANDIDATES = listOf(15353, 25353, 5354)

    /**
     * root-dns 不在 53 上监听；本机 DNS 由 DNAT 送到 loopback 上的独立端口，
     * 53 留给 Android tethering 的 dnsmasq。优先取非 ephemeral 端口，避免和 mDNS/客户端连接撞车。
     */
    fun allocatePort(): Int = chooseListenPort()
    internal const val MARK = "0x02000000"
    internal const val MASK = "0x02000000"
    private const val TABLE = 2025
    private const val PRIORITY = 7998
    private const val CHAIN_PRE = "mishka_rootdns_pre"
    private const val CHAIN_OUT = "mishka_rootdns_out"
    private const val CHAIN_INPUT = "mishka_rootdns_input"
    private const val CHAIN_INPUT_TEMP = "mishka_rootdns_input_tmp"
    private const val CHAIN_NAT = "mishka_rootdns_nat"
    private const val COMMENT_PREFIX = "mishka:dns:"
    private const val LEGACY_PRE = "mishka_dns_pre"
    private const val LEGACY_OUT = "mishka_dns_out"
    private const val IPT4 = "iptables -w 5"
    private const val IPT6 = "ip6tables -w 5"
    private const val SCRIPT_TIMEOUT_SECONDS = 120L

    /** 通配 DNS socket 启动前必须成功；失败保留已有入口保护，不能继续启动 listener。 */
    fun prepare(allowedTetherIfaces: List<String>) {
        requireIpv4Nat()
        val script = buildString {
            appendPreamble(this)
            appendLine("set -e")
            appendLine("trap 'exit 1' HUP INT TERM")
            appendIngressPrepare(this, allowedTetherIfaces.distinct())
            appendLine("exit 0")
        }
        val code = RootHelper.runRootScriptHeredoc(script, SCRIPT_TIMEOUT_SECONDS)
        check(code == 0) { "ROOT DNS ingress protection failed (code=$code); existing guards retained" }
        Log.i(TAG, "DNS ingress protection installed")
    }

    /** 捕获失败只回滚 mangle 和路由；listener 停止前入口保护必须保留。 */
    fun apply(appUid: Int, tetherIfaces: List<String>, allowedTetherIfaces: List<String>, rootDnsPort: Int) {
        require(appUid >= 0) { "Invalid app UID" }
        require(rootDnsPort in 1..65535) { "Invalid ROOT DNS port" }
        val code = RootHelper.runRootScriptHeredoc(
            buildApplyScript(appUid, tetherIfaces.distinct(), allowedTetherIfaces.distinct(), rootDnsPort),
            timeoutSeconds = SCRIPT_TIMEOUT_SECONDS,
        )
        check(code == 0) { "ROOT DNS installation failed (code=$code); capture rollback attempted, ingress guards retained" }
        Log.i(TAG, "Transparent DNS capture installed")
    }

    /** 只能在 listener 已停止后调用；确认全部自有规则、临时入口链及路由均已清理才返回 true。 */
    fun teardown(): Boolean {
        val script = buildString {
            appendPreamble(this)
            appendLine("dns_cleanup")
            appendLine("dns_verify_clean all || exit 1")
            appendLine("exit 0")
        }
        val clean = RootHelper.runRootScriptHeredoc(script, SCRIPT_TIMEOUT_SECONDS) == 0
        if (clean) Log.i(TAG, "DNS teardown verified clean")
        else Log.e(TAG, "DNS teardown failed or could not verify absence of owned rules")
        return clean
    }

    private fun tag(label: String) = "-m comment --comment '$COMMENT_PREFIX$label'"

    private fun buildApplyScript(
        appUid: Int,
        tetherIfaces: List<String>,
        allowedTetherIfaces: List<String>,
        rootDnsPort: Int,
    ): String = buildString {
        appendPreamble(this)
        appendLine(
            """
            dns_rollback() {
                dns_status=${'$'}?
                trap - 0 HUP INT TERM
                if [ "${'$'}dns_status" -ne 0 ]; then
                    set +e
                    echo 'ROOT DNS install failed; rolling back capture and retaining ingress guards' >&2
                    dns_capture_cleanup
                    dns_verify_clean || echo 'ROOT DNS rollback left residual rules or could not verify cleanup' >&2
                fi
                exit "${'$'}dns_status"
            }
            trap dns_rollback 0
            trap 'exit 1' HUP INT TERM
            set -e
            """.trimIndent()
        )
        appendIngressPrepare(this, allowedTetherIfaces)
        appendLine(
            """
            dns_capture_cleanup
            dns_verify_clean
            """.trimIndent()
        )
        val checks = StringBuilder()
        appendFamilyInstall(this, checks, IPT4, "ip -4", appUid, tetherIfaces, rootDnsPort)
        appendLine("if [ \"\$dns_ipv6\" = 1 ]; then")
        checks.appendLine("if [ \"\$dns_ipv6\" = 1 ]; then")
        appendFamilyInstall(this, checks, IPT6, "ip -6", appUid, tetherIfaces, rootDnsPort)
        appendLine("fi")
        checks.appendLine("fi")
        // 单个存活锚点不足以证明完整性；同一次 su 内逐条验证。
        append(checks)
        appendLine("exit 0")
    }

    private fun appendFamilyInstall(
        sb: StringBuilder,
        checks: StringBuilder,
        xt: String,
        ip: String,
        appUid: Int,
        tetherIfaces: List<String>,
        rootDnsPort: Int,
    ) {
        sb.appendLine("$ip route add local default dev lo table $TABLE")
        sb.appendLine("$ip rule add fwmark $MARK/$MASK lookup $TABLE priority $PRIORITY")
        checks.appendLine("dns_verify_policy '$ip'")
        sb.appendLine("$xt -t mangle -N $CHAIN_PRE")
        sb.appendLine("$xt -t mangle -N $CHAIN_OUT")
        // 入口链先于 listener 独立准备，捕获重建不能触碰它。
        fun rule(table: String, chain: String, args: String) {
            sb.appendLine("$xt -t $table -A $chain $args")
            checks.appendLine("$xt -t $table -C $chain $args")
        }
        // ACCEPT 结束当前 mangle hook，避免后续 TPROXY 链重新分类 DNS。
        // 不匹配 supplementary groups：netd 主 GID 为 0，但附加组也可能包含 net_admin。
        rule("mangle", CHAIN_OUT, "-m owner --uid-owner 0 --gid-owner ${RootHelper.CORE_GID} -j RETURN ${tag("out-core")}")
        if (appUid != 0) {
            rule("mangle", CHAIN_OUT, "-m owner --uid-owner $appUid -j RETURN ${tag("out-app")}")
        }
        for (proto in listOf("udp", "tcp")) {
            rule("mangle", CHAIN_OUT, "-p $proto --dport $DNS_PORT -j MARK --set-xmark $MARK/$MASK ${tag("out-mark-$proto")}")
            rule("mangle", CHAIN_OUT, "-p $proto --dport $DNS_PORT -j ACCEPT ${tag("out-accept-$proto")}")
        }
        rule("mangle", CHAIN_PRE, "-i lo -m mark --mark $MARK/$MASK -j ACCEPT ${tag("pre-loopback")}")
        for (iface in tetherIfaces) {
            val escaped = RootHelper.escapeShellSingleQuoted(iface)
            for (proto in listOf("udp", "tcp")) {
                rule("mangle", CHAIN_PRE, "-i $escaped -p $proto --dport $DNS_PORT -j MARK --set-xmark $MARK/$MASK ${tag("pre-mark-$proto")}")
                rule("mangle", CHAIN_PRE, "-i $escaped -p $proto --dport $DNS_PORT -j ACCEPT ${tag("pre-accept-$proto")}")
            }
        }
        // BYPASS 接口只进入入口白名单，不在这里获得透明捕获标记。
        for ((table, parent, chain) in listOf(
            Triple("mangle", "OUTPUT", CHAIN_OUT),
            Triple("mangle", "PREROUTING", CHAIN_PRE),
        )) {
            val args = "-j $chain ${tag("hook-$parent")}"
            sb.appendLine("$xt -t $table -I $parent 1 $args")
            checks.appendLine("$xt -t $table -C $parent $args")
        }

        // dnsmasq 需要占用热点网关的 53；本机 DNS 改投 loopback 上的独立端口，
        // 不能让 root listener 继续占 0.0.0.0:53，否则 SoftAp 起来后会立刻被 Tethering 关闭。
        val dnatTarget = if (xt == IPT6) "[::1]:$rootDnsPort" else "127.0.0.1:$rootDnsPort"
        val natGuard = xt == IPT6
        if (natGuard) {
            sb.appendLine("if [ \"\$dns_ipv6_nat\" = 1 ]; then")
            checks.appendLine("if [ \"\$dns_ipv6_nat\" = 1 ]; then")
        }
        sb.appendLine("$xt -t nat -N $CHAIN_NAT")
        for (proto in listOf("udp", "tcp")) {
            val args = "-p $proto --dport $DNS_PORT -m mark --mark $MARK/$MASK " +
                "-j DNAT --to-destination $dnatTarget ${tag("nat-$proto")}"
            sb.appendLine("$xt -t nat -A $CHAIN_NAT $args")
            checks.appendLine("$xt -t nat -C $CHAIN_NAT $args")
        }
        val natHook = "-j $CHAIN_NAT ${tag("hook-nat-OUTPUT")}"
        sb.appendLine("$xt -t nat -I OUTPUT 1 $natHook")
        checks.appendLine("$xt -t nat -C OUTPUT $natHook")
        if (natGuard) {
            sb.appendLine("fi")
            checks.appendLine("fi")
        }
    }

    private fun appendIngressPrepare(sb: StringBuilder, allowedTetherIfaces: List<String>) {
        appendFamilyIngressPrepare(sb, IPT4, allowedTetherIfaces)
        sb.appendLine("if [ \"\$dns_ipv6\" = 1 ]; then")
        appendFamilyIngressPrepare(sb, IPT6, allowedTetherIfaces)
        sb.appendLine("fi")
    }

    private fun appendFamilyIngressPrepare(sb: StringBuilder, xt: String, allowedTetherIfaces: List<String>) {
        // 中断可能留下正在保护 listener 的临时链；先保证永久链可终止全部 DNS，才能回收临时链。
        sb.appendLine("dns_input_state=\$($xt -t filter -S)")
        sb.appendLine("if ! printf '%s\\n' \"\$dns_input_state\" | grep -qx -- '-N $CHAIN_INPUT'; then")
        appendInputChain(sb, xt, CHAIN_INPUT, allowedTetherIfaces)
        sb.appendLine("else")
        val reject = "-j REJECT ${tag("input-reject")}"
        sb.appendLine("$xt -t filter -C $CHAIN_INPUT $reject 2>/dev/null || $xt -t filter -A $CHAIN_INPUT $reject")
        sb.appendLine("fi")
        sb.appendLine("$xt -t filter -C $CHAIN_INPUT $reject")
        appendInputHooks(sb, xt, CHAIN_INPUT)
        sb.appendLine("dns_remove_input_guard '$xt' '$CHAIN_INPUT_TEMP'")

        // 新白名单完整建好并挂到 INPUT 最前面后，才允许删除旧链；每条分支均 ACCEPT/REJECT 终止。
        appendInputChain(sb, xt, CHAIN_INPUT_TEMP, allowedTetherIfaces)
        appendInputChecks(sb, xt, CHAIN_INPUT_TEMP, allowedTetherIfaces)
        appendInputHooks(sb, xt, CHAIN_INPUT_TEMP)
        sb.appendLine("dns_remove_input_guard '$xt' '$CHAIN_INPUT'")
        // 重命名与引用更新由同一次 filter 表提交完成；失败仍由临时链保护入口。
        sb.appendLine("$xt -t filter -E $CHAIN_INPUT_TEMP $CHAIN_INPUT")
        appendInputChecks(sb, xt, CHAIN_INPUT, allowedTetherIfaces)
        for (proto in listOf("udp", "tcp")) {
            sb.appendLine("$xt -t filter -C INPUT -p $proto --dport $DNS_PORT -j $CHAIN_INPUT ${tag("hook-input-$proto")}")
        }
        sb.appendLine("dns_input_state=\$($xt -t filter -S)")
        sb.appendLine("if printf '%s\\n' \"\$dns_input_state\" | grep -Fq '$CHAIN_INPUT_TEMP'; then exit 1; fi")
    }

    private fun appendInputChain(sb: StringBuilder, xt: String, chain: String, allowedTetherIfaces: List<String>) {
        sb.appendLine("$xt -t filter -N $chain")
        appendInputRules(sb, xt, "-A", chain, allowedTetherIfaces)
    }

    private fun appendInputChecks(sb: StringBuilder, xt: String, chain: String, allowedTetherIfaces: List<String>) {
        appendInputRules(sb, xt, "-C", chain, allowedTetherIfaces)
    }

    private fun appendInputRules(
        sb: StringBuilder,
        xt: String,
        operation: String,
        chain: String,
        allowedTetherIfaces: List<String>,
    ) {
        // lo 承载本机与策略路由回送的查询；BYPASS 热点仍须能查询网关的真实 DNS。
        sb.appendLine("$xt -t filter $operation $chain -i lo -j ACCEPT ${tag("input-loopback")}")
        for (iface in allowedTetherIfaces) {
            val escaped = RootHelper.escapeShellSingleQuoted(iface)
            sb.appendLine("$xt -t filter $operation $chain -i $escaped -j ACCEPT ${tag("input-tether")}")
        }
        sb.appendLine("$xt -t filter $operation $chain -j REJECT ${tag("input-reject")}")
    }

    private fun appendInputHooks(sb: StringBuilder, xt: String, chain: String) {
        for (proto in listOf("udp", "tcp")) {
            val args = "-p $proto --dport $DNS_PORT -j $chain ${tag("hook-input-$proto")}"
            sb.appendLine("$xt -t filter -I INPUT 1 $args")
            sb.appendLine("$xt -t filter -C INPUT $args")
        }
    }

    /** shell 临时变量使用独立前缀，避免清理函数覆盖调用方状态。 */
    private fun chooseListenPort(): Int {
        for (port in PORT_CANDIDATES) {
            val tcpFree = runCatching {
                ServerSocket().use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress("127.0.0.1", port))
                }
            }.isSuccess
            if (!tcpFree) continue
            val udpFree = runCatching {
                DatagramSocket(InetSocketAddress("127.0.0.1", port)).use { }
            }.isSuccess
            if (udpFree) return port
        }
        return FALLBACK_PORT
    }

    /** IPv4 DNAT 是 ROOT DNS 的必要条件；没有 nat 表时该内核无法保留 53 给 dnsmasq。 */
    private fun requireIpv4Nat() {
        val code = RootHelper.runRootScriptHeredoc(
            """
            #!/system/bin/sh
            set +e
            iptables -w 5 -t nat -S >/dev/null 2>&1
            """.trimIndent(),
            timeoutSeconds = 60,
        )
        check(code == 0) { "ROOT DNS requires an IPv4 iptables nat table" }
    }

    private fun appendPreamble(sb: StringBuilder) {
        sb.appendLine("#!/system/bin/sh")
        sb.appendLine("set +e")
        // 接口关闭 IPv6 或没有全局地址，不等于内核缺少 IPv6 协议族。
        sb.appendLine("dns_ipv6=0")
        sb.appendLine("if [ -d /proc/sys/net/ipv6 ] || [ -f /proc/net/if_inet6 ]; then dns_ipv6=1; fi")
        // 部分 Android 内核没有 ip6_tables nat；没有 nat 表时只跳过 IPv6 DNAT，不能中止整个 ROOT DNS。
        sb.appendLine("dns_ipv6_nat=0")
        sb.appendLine("if [ \"\$dns_ipv6\" = 1 ] && $IPT6 -t nat -S >/dev/null 2>&1; then dns_ipv6_nat=1; fi")
        sb.appendLine(
            """
            dns_nat_snapshot() {
                dns_nat_state=${'$'}(${'$'}1 -t nat -S 2>/dev/null)
                dns_nat_status=${'$'}?
                if [ "${'$'}dns_nat_status" -ne 0 ]; then
                    # 仅当内核确认 NAT 表未加载时才允许查询失败；不能吞掉锁或权限错误。
                    [ -r "${'$'}2" ] && ! grep -qx nat "${'$'}2" || return 1
                    dns_nat_state=''
                fi
            }
            dns_verify_policy() {
                dns_rules=${'$'}(${'$'}1 rule show) || return 1
                printf '%s\n' "${'$'}dns_rules" | grep -Eq '^$PRIORITY:[[:space:]].*fwmark 0x2000000/0x2000000.*lookup $TABLE([[:space:]]|${'$'})' || return 1
                dns_routes=${'$'}(${'$'}1 route show table all) || return 1
                printf '%s\n' "${'$'}dns_routes" | grep -Eq '^local (default|0\.0\.0\.0/0|::/0).*dev lo.*table $TABLE([[:space:]]|${'$'})' || return 1
            }
            dns_remove_input_guard() {
                dns_guard_xt=${'$'}1
                dns_guard_chain=${'$'}2
                for dns_guard_proto in udp tcp; do
                    dns_guard_i=0
                    while [ "${'$'}dns_guard_i" -lt 32 ] && ${'$'}dns_guard_xt -t filter -C INPUT -p "${'$'}dns_guard_proto" --dport $DNS_PORT -j "${'$'}dns_guard_chain" -m comment --comment "${COMMENT_PREFIX}hook-input-${'$'}dns_guard_proto" 2>/dev/null; do
                        ${'$'}dns_guard_xt -t filter -D INPUT -p "${'$'}dns_guard_proto" --dport $DNS_PORT -j "${'$'}dns_guard_chain" -m comment --comment "${COMMENT_PREFIX}hook-input-${'$'}dns_guard_proto" 2>/dev/null || return 1
                        dns_guard_i=${'$'}((dns_guard_i+1))
                    done
                done
                dns_guard_state=${'$'}(${'$'}dns_guard_xt -t filter -S) || return 1
                # 自有 hook 必须确实消失；未知引用不能通过清空链来绕过。
                if printf '%s\n' "${'$'}dns_guard_state" | grep -Eq "^-A .* -[jg] ${'$'}dns_guard_chain([[:space:]]|${'$'})"; then return 1; fi
                if printf '%s\n' "${'$'}dns_guard_state" | grep -qx -- "-N ${'$'}dns_guard_chain"; then
                    ${'$'}dns_guard_xt -t filter -F "${'$'}dns_guard_chain" || return 1
                    ${'$'}dns_guard_xt -t filter -X "${'$'}dns_guard_chain" || return 1
                fi
                dns_guard_state=${'$'}(${'$'}dns_guard_xt -t filter -S) || return 1
                if printf '%s\n' "${'$'}dns_guard_state" | grep -Eq "(^|[[:space:]])${'$'}dns_guard_chain([[:space:]]|${'$'})"; then return 1; fi
                return 0
            }
            dns_capture_cleanup() {
            """.trimIndent()
        )
        appendFamilyCleanup(sb, IPT4, "ip -4")
        sb.appendLine("if [ \"\$dns_ipv6\" = 1 ]; then")
        appendFamilyCleanup(sb, IPT6, "ip -6")
        sb.appendLine("fi")
        sb.appendLine(":")
        sb.appendLine("}")
        sb.appendLine("dns_input_cleanup() {")
        sb.appendLine("dns_input_cleanup_status=0")
        for (chain in listOf(CHAIN_INPUT, CHAIN_INPUT_TEMP)) {
            sb.appendLine("dns_remove_input_guard '$IPT4' '$chain' || dns_input_cleanup_status=1")
            sb.appendLine("if [ \"\$dns_ipv6\" = 1 ]; then")
            sb.appendLine("dns_remove_input_guard '$IPT6' '$chain' || dns_input_cleanup_status=1")
            sb.appendLine("fi")
        }
        sb.appendLine("return \"\$dns_input_cleanup_status\"")
        sb.appendLine("}")
        sb.appendLine("dns_cleanup() {")
        sb.appendLine("dns_capture_cleanup")
        sb.appendLine("dns_input_cleanup")
        sb.appendLine("}")
        sb.appendLine("dns_verify_clean() {")
        appendFamilyVerifyClean(sb, IPT4, "ip -4", "/proc/net/ip_tables_names")
        sb.appendLine("if [ \"\$dns_ipv6\" = 1 ]; then")
        appendFamilyVerifyClean(sb, IPT6, "ip -6", "/proc/net/ip6_tables_names")
        sb.appendLine("fi")
        sb.appendLine("return 0")
        sb.appendLine("}")
    }

    private fun appendFamilyCleanup(sb: StringBuilder, xt: String, ip: String) {
        fun removeRule(table: String, parent: String, args: String) {
            sb.appendLine("dns_i=0; while [ \$dns_i -lt 32 ] && $xt -t $table -C $parent $args 2>/dev/null; do")
            sb.appendLine("$xt -t $table -D $parent $args 2>/dev/null || break")
            sb.appendLine("dns_i=\$((dns_i+1)); done")
        }
        removeRule("mangle", "OUTPUT", "-j $CHAIN_OUT ${tag("hook-OUTPUT")}")
        removeRule("mangle", "PREROUTING", "-j $CHAIN_PRE ${tag("hook-PREROUTING")}")
        for (chain in listOf(CHAIN_OUT, CHAIN_PRE)) {
            sb.appendLine("$xt -t mangle -F $chain 2>/dev/null || true")
            sb.appendLine("$xt -t mangle -X $chain 2>/dev/null || true")
        }
        // 旧 DNS NAT hook 无标签，只删除精确匹配的自有 hook 与链。
        val names = if (ip == "ip -4") "/proc/net/ip_tables_names" else "/proc/net/ip6_tables_names"
        sb.appendLine("if dns_nat_snapshot '$xt' '$names' && [ -n \"\$dns_nat_state\" ]; then")
        removeRule("nat", "PREROUTING", "-j $LEGACY_PRE")
        removeRule("nat", "OUTPUT", "-j $LEGACY_OUT")
        for (chain in listOf(LEGACY_PRE, LEGACY_OUT)) {
            sb.appendLine("$xt -t nat -F $chain 2>/dev/null || true")
            sb.appendLine("$xt -t nat -X $chain 2>/dev/null || true")
        }
        sb.appendLine("fi")
        removeRule("nat", "OUTPUT", "-j $CHAIN_NAT ${tag("hook-nat-OUTPUT")}")
        sb.appendLine("$xt -t nat -F $CHAIN_NAT 2>/dev/null || true")
        sb.appendLine("$xt -t nat -X $CHAIN_NAT 2>/dev/null || true")
        sb.appendLine("dns_i=0; while [ \$dns_i -lt 32 ] && $ip rule del fwmark $MARK/$MASK lookup $TABLE priority $PRIORITY 2>/dev/null; do dns_i=\$((dns_i+1)); done")
        sb.appendLine("$ip route del local default dev lo table $TABLE 2>/dev/null || true")
    }

    private fun appendFamilyVerifyClean(sb: StringBuilder, xt: String, ip: String, names: String) {
        sb.appendLine("dns_state=\$($xt -t mangle -S) || return 1")
        sb.appendLine("if printf '%s\\n' \"\$dns_state\" | grep -Eq '$COMMENT_PREFIX|$CHAIN_PRE|$CHAIN_OUT'; then return 1; fi")
        // 捕获回滚保留 filter 保护；只有 listener 停止后的完整清理才要求入口链也消失。
        sb.appendLine("if [ \"\${1:-capture}\" = all ]; then")
        sb.appendLine("dns_state=\$($xt -t filter -S) || return 1")
        sb.appendLine("if printf '%s\\n' \"\$dns_state\" | grep -Eq '$COMMENT_PREFIX|$CHAIN_INPUT|$CHAIN_INPUT_TEMP'; then return 1; fi")
        sb.appendLine("fi")
        sb.appendLine("dns_nat_snapshot '$xt' '$names' || return 1")
        sb.appendLine("if printf '%s\\n' \"\$dns_nat_state\" | grep -Eq '$LEGACY_PRE|$LEGACY_OUT|mishka:tproxy:dns-'; then return 1; fi")
        sb.appendLine("dns_rules=\$($ip rule show) || return 1")
        sb.appendLine("if printf '%s\\n' \"\$dns_rules\" | grep -Eq '^$PRIORITY:[[:space:]].*fwmark 0x2000000/0x2000000.*lookup $TABLE([[:space:]]|\$)'; then return 1; fi")
        sb.appendLine("dns_routes=\$($ip route show table all) || return 1")
        sb.appendLine("if printf '%s\\n' \"\$dns_routes\" | grep -Eq '^local (default|0\\.0\\.0\\.0/0|::/0).*dev lo.*table $TABLE([[:space:]]|\$)'; then return 1; fi")
    }
}
