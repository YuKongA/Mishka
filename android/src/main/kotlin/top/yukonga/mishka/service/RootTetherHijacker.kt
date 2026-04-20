package top.yukonga.mishka.service

import android.util.Log
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * ROOT 模式下的热点流量处置。
 *
 * sing-tun `auto_route=true` 加的 catch-all 规则 (`NOT iif lo → lookup 2022`) 不区分
 * 本机 vs 转发流量，热点客户端包也命中该规则被导进 TUN 表，但 mihomo 对非本机源 IP
 * 的处理不稳（性能差 / 黑洞）。本类通过 `ip rule` / `iptables` 在 sing-tun 规则之前
 * 插队，分两种模式处理热点接口的流量：
 *
 * - BYPASS：在 ip rule priority 8000-8003 插 `goto 9010`（sing-tun 自己加的 nop marker），
 *   越过 catch-all 后命中 Android 原生的 iif-based forward 规则
 *   （`iif <tether> lookup <upstream>`，priority ~21000），让热点流量走手机当前
 *   default interface（wlan0/rmnet 等），完全不碰 mihomo。
 * - PROXY：优先使用 mangle PREROUTING 的 `-j TPROXY` 内核态透明代理，把热点 TCP+UDP
 *   导到 mihomo 的 tproxy-port（`IP_TRANSPARENT` socket），绕开 sing-tun userspace
 *   TCP stack，延迟/吞吐接近 BYPASS。xt_TPROXY 内核模块不可用时退回到"去程+回程都
 *   `lookup 2022`"的 ip rule 对称路径（仍走 userspace stack，性能次于 TPROXY 但
 *   连接不再断，修掉了历史版本"去程进 TUN / 回程 goto 9010"的非对称 bug）。
 *
 * 注意 **不能** 用 `lookup main`：Android 的 main 表没有 default route（default 分散在
 * 各 upstream 独立表 wlan0/rmnet_data0 里），命中 main 会立即丢包 → 完全无网。
 *
 * 生命周期：startProxy 成功 → `apply`；stop/死亡路径 → `teardown`。
 * 幂等：重复 apply 同 priority / 同名 chain 会 stderr 报错但内核规则仍单份；teardown
 * 不依赖内存状态，按 priority / chain 名批量删。
 */
object RootTetherHijacker {

    private const val TAG = "RootTetherHijacker"

    // ========== BYPASS + fallback ip rule 路径 ==========
    // 低于 sing-tun 默认 rule index 9000，保证先匹配（数字越小优先级越高）
    private const val PRIORITY_V4 = 8000          // 去程：iif <tether>
    private const val PRIORITY_V6 = 8001
    private const val PRIORITY_RETURN_V4 = 8002   // 回程：to <hotspot_subnet>
    private const val PRIORITY_RETURN_V6 = 8003
    private const val TUN_TABLE = RuntimeOverrideBuilder.ROOT_TUN_TABLE
    // sing-tun 内部 nop marker（ruleStart + 10），goto 它会越过 sing-tun 的 9002 catch-all
    // 然后继续匹配后面的 Android 原生规则（iif-based forward rule at ~21000）
    private const val BYPASS_GOTO_TARGET = RuntimeOverrideBuilder.ROOT_TUN_RULE_INDEX + 10

    // ========== TPROXY 路径 ==========
    // mihomo tproxy listener 固定端口，与 mixed-port / redir-port 错开；
    // RuntimeOverrideBuilder 根据 probe 结果决定是否往 override 里写入该端口
    internal const val TPROXY_PORT = 7895
    // bit 24，避开 Android Netd 低 16 位 mark（permissions / explicit-socket / netId 等）。
    // 与 box_for_magisk / Surfing / ClashforMagisk 选值一致（0x01000000/0x01000000）。
    private const val TPROXY_MARK = 0x01000000
    private const val TPROXY_MASK = 0x01000000
    // fwmark 专用 route table；与 sing-tun 的 2022 / 9000 完全错开，
    // Android 内置 ip rule priority 从 10000+ 起，7999 安全
    private const val FWMARK_TABLE = 2024
    private const val PRIORITY_FWMARK = 7999
    // 自定义 mangle chain 名
    private const val CHAIN_NAME = "mishka_tether"

    const val DEFAULT_IFACES = "wlan1,wlan2"

    enum class Mode(val storageValue: String) {
        BYPASS("bypass"),
        PROXY("proxy");

        companion object {
            fun from(value: String): Mode = entries.firstOrNull { it.storageValue == value } ?: BYPASS
        }
    }

    /**
     * 探测内核是否支持 xt_TPROXY。策略：创建临时 mangle chain，尝试追加一条
     * TPROXY 规则，以该追加命令的返回码判断；之后清理 chain（失败也忽略）。
     * 幂等、零副作用，可重复调用。
     */
    fun probeTproxySupport(): Boolean {
        val probeName = "mishka_probe_${System.currentTimeMillis() % 1_000_000}"
        val cmd = "iptables -t mangle -N $probeName 2>/dev/null; " +
            "iptables -t mangle -A $probeName -p tcp -j TPROXY " +
            "--on-ip 127.0.0.1 --on-port 1 --tproxy-mark 0x1/0x1; " +
            "rc=\$?; " +
            "iptables -t mangle -F $probeName 2>/dev/null; " +
            "iptables -t mangle -X $probeName 2>/dev/null; " +
            "exit \$rc"
        val r = runWithOutput(cmd)
        val ok = r.code == 0
        Log.i(TAG, "probeTproxySupport: supported=$ok code=${r.code} out=${r.output.oneLine()}")
        return ok
    }

    /**
     * 对 interfaces 中每个接口安装对应模式的路由 / iptables 规则。
     * 接口不存在或规则已存在时 stderr 报错，捕获到日志便于排查（EEXIST 是预期）。
     *
     * @param tproxySupported 由 caller（MishkaRootService）提前 probe 得出的结果。
     *        仅 PROXY 模式参考；BYPASS 分支不读。
     */
    fun apply(mode: Mode, interfaces: List<String>, tproxySupported: Boolean) {
        if (interfaces.isEmpty()) {
            Log.i(TAG, "apply: no interfaces configured, skip (mode=$mode)")
            return
        }
        Log.i(TAG, "Applying tether rules: mode=$mode tproxySupported=$tproxySupported ifaces=$interfaces")
        when (mode) {
            Mode.BYPASS -> applyBypass(interfaces)
            Mode.PROXY -> if (tproxySupported) applyTproxy(interfaces) else applyProxyFallback(interfaces)
        }
        dumpState()
    }

    /**
     * BYPASS：ip rule goto sing-tun nop marker → 走 Android 原生 iif-based forward rule。
     * 去程 + 回程均 `goto 9010`，对称、无副作用。
     */
    private fun applyBypass(interfaces: List<String>) =
        applyIpRuleAction(interfaces, action = "goto $BYPASS_GOTO_TARGET", label = "bypass")

    /**
     * PROXY + TPROXY：mangle PREROUTING -i <tether> -j CHAIN，CHAIN 对 TCP+UDP 打 fwmark
     * 并 TPROXY 到 mihomo tproxy-port；fwmark 命中专用 route table（含 `local default dev lo`）
     * 触发本机投递，kernel 在 PREROUTING 里就把 socket 查找指向了 IP_TRANSPARENT listener。
     *
     * `ip route add local default dev lo table <X>` 是 TPROXY 套路的命门 —— 没有这条
     * 带 fwmark 的包找不到接收 socket，直接被丢。
     */
    private fun applyTproxy(interfaces: List<String>) {
        // 幂等：清理残留（旧版升级路径 / 上次异常退出）
        teardownTproxy()

        // 1. 专用 mangle chain
        for (cmd in listOf("iptables", "ip6tables")) {
            runWithOutput("$cmd -t mangle -N $CHAIN_NAME")
        }
        val onIp4 = "127.0.0.1"
        val onIp6 = "::1"
        runWithOutput(
            "iptables -t mangle -A $CHAIN_NAME -p tcp -j TPROXY " +
                "--on-ip $onIp4 --on-port $TPROXY_PORT --tproxy-mark $TPROXY_MARK/$TPROXY_MASK"
        )
        runWithOutput(
            "iptables -t mangle -A $CHAIN_NAME -p udp -j TPROXY " +
                "--on-ip $onIp4 --on-port $TPROXY_PORT --tproxy-mark $TPROXY_MARK/$TPROXY_MASK"
        )
        runWithOutput(
            "ip6tables -t mangle -A $CHAIN_NAME -p tcp -j TPROXY " +
                "--on-ip $onIp6 --on-port $TPROXY_PORT --tproxy-mark $TPROXY_MARK/$TPROXY_MASK"
        )
        runWithOutput(
            "ip6tables -t mangle -A $CHAIN_NAME -p udp -j TPROXY " +
                "--on-ip $onIp6 --on-port $TPROXY_PORT --tproxy-mark $TPROXY_MARK/$TPROXY_MASK"
        )

        // 2. 每个热点接口挂 PREROUTING → 跳 CHAIN
        for (iface in interfaces) {
            val escaped = RootHelper.escapeShellSingleQuoted(iface)
            val v4 = runWithOutput("iptables -t mangle -A PREROUTING -i $escaped -j $CHAIN_NAME")
            val v6 = runWithOutput("ip6tables -t mangle -A PREROUTING -i $escaped -j $CHAIN_NAME")
            Log.i(TAG, "tproxy iif=$iface v4[code=${v4.code}, out=${v4.output.oneLine()}] v6[code=${v6.code}, out=${v6.output.oneLine()}]")
        }

        // 3. 策略路由：fwmark → 专用表；表里声明整个地址空间为 local → 内核在 PREROUTING
        //    重新查路由时会把包判定为"本机投递"，从而触发 TPROXY socket 匹配
        runWithOutput("ip rule add fwmark $TPROXY_MARK/$TPROXY_MASK lookup $FWMARK_TABLE priority $PRIORITY_FWMARK")
        runWithOutput("ip -6 rule add fwmark $TPROXY_MARK/$TPROXY_MASK lookup $FWMARK_TABLE priority $PRIORITY_FWMARK")
        runWithOutput("ip route add local default dev lo table $FWMARK_TABLE")
        runWithOutput("ip -6 route add local default dev lo table $FWMARK_TABLE")
    }

    /**
     * PROXY 降级路径：xt_TPROXY 不可用时使用。去程 + 回程都 `lookup 2022`，双向对称
     * 进 TUN，由 sing-tun userspace stack 处理。性能次于 TPROXY，但连接可用。
     * 不额外加 MASQUERADE：mihomo TUN 出站本就从本机 IP 发起，无非对称 NAT 风险。
     */
    private fun applyProxyFallback(interfaces: List<String>) =
        applyIpRuleAction(interfaces, action = "lookup $TUN_TABLE", label = "proxy-fallback")

    /**
     * BYPASS 和 PROXY-fallback 共用的 ip rule 装配逻辑。对每个接口插入对称的
     * 去程（iif <tether>）+ 回程（to <subnet>）规则，仅 action 不同：
     * - BYPASS：`goto 9010`
     * - PROXY-fallback：`lookup 2022`
     *
     * 回程 subnet 靠 NetworkInterface 读取；接口未就绪时 WARN 并跳过回程规则，
     * 用户需在热点起来后 restart 代理触发重新 apply。
     */
    private fun applyIpRuleAction(interfaces: List<String>, action: String, label: String) {
        for (iface in interfaces) {
            val escaped = RootHelper.escapeShellSingleQuoted(iface)
            val v4 = runWithOutput("ip rule add iif $escaped $action priority $PRIORITY_V4")
            val v6 = runWithOutput("ip -6 rule add iif $escaped $action priority $PRIORITY_V6")
            Log.i(TAG, "$label iif=$iface v4[code=${v4.code}, out=${v4.output.oneLine()}] v6[code=${v6.code}, out=${v6.output.oneLine()}]")

            val v4Subnets = resolveSubnets(iface, v6 = false)
            val v6Subnets = resolveSubnets(iface, v6 = true)
            for (net in v4Subnets) {
                val r = runWithOutput("ip rule add to $net $action priority $PRIORITY_RETURN_V4")
                Log.i(TAG, "$label return to=$net code=${r.code} out=${r.output.oneLine()}")
            }
            for (net in v6Subnets) {
                val r = runWithOutput("ip -6 rule add to $net $action priority $PRIORITY_RETURN_V6")
                Log.i(TAG, "$label return (v6) to=$net code=${r.code} out=${r.output.oneLine()}")
            }
            if (v4Subnets.isEmpty() && v6Subnets.isEmpty()) {
                Log.w(TAG, "iif=$iface has no resolvable subnet (interface down or no IP), return-path rule skipped")
            }
        }
    }

    /**
     * 读 `NetworkInterface.getByName(iface).interfaceAddresses`，按 prefix 长度算出 CIDR
     * 网络号。接口不存在或无地址时返回空。
     */
    private fun resolveSubnets(iface: String, v6: Boolean): List<String> {
        return try {
            val ni = NetworkInterface.getByName(iface) ?: return emptyList()
            ni.interfaceAddresses.mapNotNull { addr ->
                val inet = addr.address ?: return@mapNotNull null
                if (inet.isLoopbackAddress || inet.isLinkLocalAddress || inet.isAnyLocalAddress) return@mapNotNull null
                val isV4 = inet is Inet4Address
                val isV6 = inet is Inet6Address
                if (v6 && !isV6) return@mapNotNull null
                if (!v6 && !isV4) return@mapNotNull null
                val prefix = addr.networkPrefixLength.toInt()
                if (prefix <= 0) return@mapNotNull null
                cidrNetwork(inet.address, prefix)?.let { "$it/$prefix" }
            }.distinct()
        } catch (e: Exception) {
            Log.w(TAG, "resolveSubnets $iface failed", e)
            emptyList()
        }
    }

    /** 把 raw 地址字节按 prefix 长度掩码，返回点分（v4）或冒号（v6）表示。 */
    private fun cidrNetwork(bytes: ByteArray, prefix: Int): String? {
        if (bytes.size != 4 && bytes.size != 16) return null
        val out = bytes.copyOf()
        for (i in out.indices) {
            val keep = (prefix - i * 8).coerceIn(0, 8)
            val mask = if (keep == 0) 0 else (0xFF shl (8 - keep)) and 0xFF
            out[i] = (out[i].toInt() and mask).toByte()
        }
        return if (out.size == 4) {
            out.joinToString(".") { (it.toInt() and 0xFF).toString() }
        } else {
            // v6：按 16-bit groups
            (0 until 8).joinToString(":") {
                val hi = out[it * 2].toInt() and 0xFF
                val lo = out[it * 2 + 1].toInt() and 0xFF
                ((hi shl 8) or lo).toString(16)
            }
        }
    }

    /**
     * 全路径清理：BYPASS ip rule + fallback ip rule + TPROXY chain & 策略路由。
     * 两类都跑，保证从任意前置状态清理干净（例如从旧版 PROXY 升级、xt_TPROXY 时有时无）。
     */
    fun teardown() {
        Log.i(TAG, "Tearing down tether rules")
        teardownIpRulesByPriority()
        teardownTproxy()
    }

    /** 清理 BYPASS 和 PROXY-fallback 共用的 8000-8003 priority ip rule。 */
    private fun teardownIpRulesByPriority() {
        val counts = intArrayOf(
            drainRulesByPriority(v6 = false, priority = PRIORITY_V4),
            drainRulesByPriority(v6 = true, priority = PRIORITY_V6),
            drainRulesByPriority(v6 = false, priority = PRIORITY_RETURN_V4),
            drainRulesByPriority(v6 = true, priority = PRIORITY_RETURN_V6),
        )
        Log.i(TAG, "teardown ip-rule removed iif-v4=${counts[0]} iif-v6=${counts[1]} return-v4=${counts[2]} return-v6=${counts[3]}")
    }

    /** 清理 TPROXY 相关 iptables chain、fwmark ip rule、专用 route table。 */
    private fun teardownTproxy() {
        // 先拆 PREROUTING jump 再 flush/delete chain —— 被引用的 chain 不能 -X
        for (bin in listOf("iptables", "ip6tables")) {
            var n = 0
            while (n < 32) {
                val r = runWithOutput("$bin -t mangle -D PREROUTING -j $CHAIN_NAME")
                if (r.code != 0) break
                n++
            }
            runWithOutput("$bin -t mangle -F $CHAIN_NAME")
            runWithOutput("$bin -t mangle -X $CHAIN_NAME")
        }
        // fwmark ip rule
        drainRulesByPriority(v6 = false, priority = PRIORITY_FWMARK)
        drainRulesByPriority(v6 = true, priority = PRIORITY_FWMARK)
        // 专用 route table
        runWithOutput("ip route flush table $FWMARK_TABLE")
        runWithOutput("ip -6 route flush table $FWMARK_TABLE")
    }

    private fun drainRulesByPriority(v6: Boolean, priority: Int): Int {
        val cmd = if (v6) "ip -6 rule del priority $priority" else "ip rule del priority $priority"
        var n = 0
        while (n < 32) {
            val r = runWithOutput(cmd)
            if (r.code != 0) break
            n++
        }
        return n
    }

    fun parseInterfaces(raw: String): List<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** 启动代理后 dump 一次完整路由状态，便于诊断"规则加了但没生效"类问题。 */
    private fun dumpState() {
        val rules = runWithOutput("ip rule show").output
        val rules6 = runWithOutput("ip -6 rule show").output
        Log.i(TAG, "ip rule show (v4):\n$rules")
        Log.i(TAG, "ip -6 rule show:\n$rules6")
        val mainRoute = runWithOutput("ip route show table main").output
        val tunRoute = runWithOutput("ip route show table $TUN_TABLE").output
        val tproxyRoute = runWithOutput("ip route show table $FWMARK_TABLE").output
        Log.i(TAG, "route table main:\n$mainRoute")
        Log.i(TAG, "route table $TUN_TABLE:\n$tunRoute")
        Log.i(TAG, "route table $FWMARK_TABLE:\n$tproxyRoute")
        val mangle = runWithOutput("iptables -t mangle -S").output
        Log.i(TAG, "iptables mangle -S:\n$mangle")
    }

    private data class ShellResult(val code: Int, val output: String)

    private fun runWithOutput(command: String, timeoutSeconds: Long = 5): ShellResult {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)   // 合并 stderr，失败原因会进 output
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                return ShellResult(-1, "<timeout>\n$output")
            }
            ShellResult(process.exitValue(), output)
        } catch (e: Exception) {
            ShellResult(-1, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun String.oneLine(): String =
        if (isEmpty()) "<ok>" else replace('\n', '|').take(120)
}
