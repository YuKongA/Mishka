package top.yukonga.mishka.service

import android.util.Log
import java.util.concurrent.TimeUnit

object RootHelper {

    private const val TAG = "RootHelper"

    internal data class ShellOutcome(val code: Int, val output: String)

    /**
     * 读干 stdout 并等待退出，超时强杀。
     *
     * 独立线程读是必须的：当前线程 `readText()` 阻塞到 EOF，而等锁的子进程永不 EOF，
     * 后面的 `waitFor(timeout)` 就成了死代码。只等不读则撑爆 64KB 管道缓冲。
     */
    internal fun awaitDrained(process: Process, timeoutSeconds: Long): ShellOutcome {
        val buffer = StringBuffer()
        val drain = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { buffer.append(it).append('\n') }
            }
        }.apply { isDaemon = true; start() }

        val exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            drain.join(DRAIN_JOIN_MS)
            return ShellOutcome(-1, "<timeout>\n$buffer")
        }
        drain.join(DRAIN_JOIN_MS)
        return ShellOutcome(process.exitValue(), buffer.toString().trim())
    }

    /** 进程退出后管道很快 EOF，给读线程一点收尾时间。 */
    private const val DRAIN_JOIN_MS = 500L

    fun hasRootAccess(): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val exited = process.waitFor(3, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                return false
            }
            val output = process.inputStream.bufferedReader().readText()
            process.exitValue() == 0 && output.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 后台启动 mihomo，重定向输出到日志文件，返回真实 PID。
     *
     * args 含密钥，逐个转义且不进日志。
     */
    fun startAsRoot(binary: String, args: Array<String>, workDir: String, logFile: String): Int {
        val argsStr = args.joinToString(" ") { escapeShellSingleQuoted(it) }
        val command = "cd ${escapeShellSingleQuoted(workDir)} || exit 1; " +
                "${escapeShellSingleQuoted(binary)} $argsStr > ${escapeShellSingleQuoted(logFile)} 2>&1 & echo \$!"
        Log.i(TAG, "Starting as root: ${redactArgs(args)}")
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val reader = process.inputStream.bufferedReader()
            val pidLine = reader.readLine()?.trim() ?: ""
            val pid = pidLine.toIntOrNull() ?: -1
            Log.i(TAG, "mihomo actual PID: $pid")
            process.inputStream.close()
            pid
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start as root: ${e.message}")
            -1
        }
    }

    fun readLogFile(logFile: String, maxLines: Int = 20): String {
        return try {
            val path = escapeShellSingleQuoted(logFile)
            val process = ProcessBuilder("su", "-c", "tail -n $maxLines $path 2>/dev/null")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(3, TimeUnit.SECONDS)
            output.trim()
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 以 root 权限读取 `/proc/$pid/cmdline`。非 root 进程无权读 root 进程的 cmdline。
     * 超时/异常返回空串，仅做 IO，不做语义判断。
     */
    fun readRootCmdline(pid: Int): String {
        return try {
            val process = ProcessBuilder("su", "-c", "cat /proc/$pid/cmdline 2>/dev/null")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(3, TimeUnit.SECONDS)
            output
        } catch (_: Exception) {
            ""
        }
    }

    fun isAliveAsRoot(pid: Int): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", "kill -0 $pid")
                .redirectErrorStream(true)
                .start()
            process.waitFor(3, TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    fun killAsRoot(pid: Int, tunDevice: String = "Mishka"): Boolean {
        try {
            Log.i(TAG, "Killing root process: pid=$pid")
            runRootCommand("kill $pid")
            for (i in 1..6) {
                Thread.sleep(500)
                if (!isAliveAsRoot(pid)) {
                    Log.i(TAG, "Process $pid terminated after SIGTERM")
                    return true
                }
            }
            // SIGKILL（进程无法优雅清理，需要手动清理 TUN 残留）
            Log.w(TAG, "Process $pid still alive after SIGTERM, sending SIGKILL")
            runRootCommand("kill -9 $pid")
            for (i in 1..4) {
                Thread.sleep(500)
                if (!isAliveAsRoot(pid)) {
                    Log.i(TAG, "Process $pid terminated after SIGKILL")
                    cleanupRootNetwork(tunDevice)
                    return true
                }
            }
            Log.e(TAG, "Process $pid still alive after SIGKILL")
            return false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to kill root process: ${e.message}")
            return false
        }
    }

    fun killMihomoByName(tunDevice: String = "Mishka") {
        try {
            Log.w(TAG, "Falling back to pkill for libmihomo_runner.so")
            runRootCommand("pkill -TERM -f libmihomo_runner.so")
            Thread.sleep(1000)
            runRootCommand("pkill -9 -f libmihomo_runner.so")
            cleanupRootNetwork(tunDevice)
        } catch (_: Exception) {
        }
    }

    /**
     * 清理 Root 模式 mihomo 被 SIGKILL 后残留的 TUN 设备和路由表。
     * SIGKILL 不给进程清理机会，需要手动清理。
     */
    private fun cleanupRootNetwork(tunDevice: String) {
        try {
            Log.i(TAG, "Cleaning up root network state")
            runRootCommand("ip link delete ${escapeShellSingleQuoted(tunDevice)} 2>/dev/null; true")
        } catch (_: Exception) {
        }
    }

    private fun runRootCommand(command: String): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process.waitFor(3, TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }


    /**
     * 清理残留的 mihomo 进程（孤儿进程，非当前 App 子进程），可选带 TUN 清理。
     * 整个流程下沉到单次 su shell 执行，避免 Kotlin 侧 Thread.sleep 轮询 + 多次 su 调用的开销。
     *
     * TUN 清理动机：SIGKILL 不给 mihomo 清理机会，残留的 TUN 设备会导致下次启动
     * sing-tun `tun.New()` 返回 EEXIST → TUN inbound 失败 → mihomo 继续运行其他
     * inbound 但实际无 TUN → UI 显示 Running 但无网（silent failure）。
     *
     * @param tunDevice 即将启动的 mihomo 配置的 TUN 设备名，清理孤儿后兜底删除该接口；null 则不清 TUN
     * exit code: 0=无残留（或已清理）；1=SIGKILL 后仍存活；其他=shell 或 su 错误。
     */
    fun cleanupOrphanedMihomo(tunDevice: String? = null) {
        val tunCleanupLine = tunDevice?.let {
            "ip link delete ${escapeShellSingleQuoted(it)} 2>/dev/null; true"
        } ?: "true"

        val script = """
            pgrep -f libmihomo_runner.so >/dev/null 2>&1 && {
                pkill -TERM -f libmihomo_runner.so 2>/dev/null
                i=0; while [ ${'$'}i -lt 6 ]; do
                    sleep 0.5
                    pgrep -f libmihomo_runner.so >/dev/null 2>&1 || break
                    i=${'$'}((i+1))
                done
                pgrep -f libmihomo_runner.so >/dev/null 2>&1 && {
                    pkill -KILL -f libmihomo_runner.so 2>/dev/null
                    i=0; while [ ${'$'}i -lt 4 ]; do
                        sleep 0.5
                        pgrep -f libmihomo_runner.so >/dev/null 2>&1 || break
                        i=${'$'}((i+1))
                    done
                }
            }
            # 进程清理后（或本就不存在孤儿），兜底清 TUN 避免下次启动 EEXIST
            $tunCleanupLine
            pgrep -f libmihomo_runner.so >/dev/null 2>&1 && exit 1
            exit 0
        """.trimIndent()

        try {
            val process = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start()
            val exited = process.waitFor(8, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                Log.e(TAG, "cleanupOrphanedMihomo timed out")
                return
            }
            if (process.exitValue() == 1) {
                Log.e(TAG, "Orphaned mihomo still alive after SIGKILL")
            }
        } catch (_: Exception) {
            // 无 su 设备 ProcessBuilder 抛 IOException，静默降级
        }
    }

    /**
     * POSIX shell 单引号转义。上游不做字符校验，这里是唯一防线：
     * 任何外部值进 `su -c` 前都要过它——双引号挡不住 `$(...)`。
     */
    internal fun escapeShellSingleQuoted(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"

    /** 密钥类参数在日志里只留 flag 名。 */
    private fun redactArgs(args: Array<String>): String {
        var maskNext = false
        return args.joinToString(" ") { arg ->
            when {
                maskNext -> "***".also { maskNext = false }
                arg == "--secret" || arg == "--age-secret-key" -> arg.also { maskNext = true }
                else -> arg
            }
        }
    }

    /**
     * 以 root 身份 rm -rf 指定路径。调用方自行保证路径语义（仅用于 app 自己的数据目录下）。
     * best-effort：无 su 设备或失败返回 false，不抛异常。
     */
    fun rmRfAsRoot(path: String): Boolean {
        return try {
            val escaped = escapeShellSingleQuoted(path)
            val process = ProcessBuilder("su", "-c", "rm -rf $escaped")
                .redirectErrorStream(true)
                .start()
            val exited = process.waitFor(8, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                return false
            }
            process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 以 root 身份通过 stdin 批量执行 shell 脚本。适合 TPROXY apply/teardown 这类
     * 一次写入几十条 iptables/ip rule 命令的场景，避免逐条 `su -c` 的进程启动开销。
     *
     * @param script shell 脚本全文（应含 shebang 或至少用 POSIX sh 语法，失败容错靠脚本内 `|| true`）
     * @param timeoutSeconds 整体执行超时；超时时强制 destroy 子进程
     * @return 脚本执行 exit code；超时或异常返回 -1
     */
    fun runRootScriptHeredoc(script: String, timeoutSeconds: Long = 15): Int {
        return try {
            val process = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
            process.outputStream.bufferedWriter().use { it.write(script); it.flush() }
            val (code, output) = awaitDrained(process, timeoutSeconds)
            if (code == -1) {
                Log.e(TAG, "runRootScriptHeredoc timed out\noutput:\n${output.take(2000)}")
            } else if (code != 0 && output.isNotBlank()) {
                Log.w(TAG, "runRootScriptHeredoc code=$code output:\n${output.take(2000)}")
            }
            code
        } catch (e: Exception) {
            Log.w(TAG, "runRootScriptHeredoc failed: ${e.message}")
            -1
        }
    }

    /**
     * 以 root 身份 chown -R 指定路径到 uid:gid（Android 应用数据目录 uid==gid）。
     * 用于一次性迁移旧版本 mihomo 以 root 权限直写入 imported/ 产生的 root:root 文件。
     */
    fun chownRecursiveAsRoot(path: String, uid: Int): Boolean {
        return try {
            val escaped = escapeShellSingleQuoted(path)
            val process = ProcessBuilder("su", "-c", "chown -R $uid:$uid $escaped")
                .redirectErrorStream(true)
                .start()
            val exited = process.waitFor(10, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                return false
            }
            process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 单次 su 把 root 写入的普通文件从 [srcBound] 拷回 [bound]。
     * 源和目标的祖先符号链接都拒绝，物理路径必须仍在对应根下；缺尾可以 mkdir，
     * 但 mkdir、chown -h、cp -P、chcon、mv 之前再查一次。chown 用 -h，避免跟随链接。
     * mtime 用 stat %y 的纳秒比较（toybox 的 %N 是长文件名）。相等则 cmp，内容不同才拷源；
     * 解析不出纳秒就失败，不用秒级 -ge/-gt。结果 chown 到 [uid]、chmod 0644，并把 SELinux
     * 标签改成与 bound 相同。从 bound 读出上下文字符串再 chcon：toybox 的 chcon 没有
     * --reference；restorecon 不带 -D 会跳过 /data/data 仍返回成功，-D 也只会标成
     * file_contexts 的 system_data_file，不是 installd 的 app_data_file。读回不一致就不发布。
     * SELinux 关闭且读不到上下文时，DAC chown 即可。父目录 chown 或标签失败则不发布该文件，
     * 不拦其它文件。chcon 失败仍读回：上下文已经一致就当成功，避免误伤已标好的目录。
     * 保留源 mtime；mtime 没保住仍发布。任一文件失败返回 false，已成功的不回滚。
     */
    fun syncRegularFiles(uid: Int, srcBound: String, bound: String, pairs: List<Pair<String, String>>): Boolean {
        if (pairs.isEmpty() || uid <= 0) return pairs.isEmpty()
        if (bound.isEmpty() || srcBound.isEmpty()) return false
        if (bound.any { it == '\n' || it == '\r' || it == '\u0000' }) return false
        if (srcBound.any { it == '\n' || it == '\r' || it == '\u0000' }) return false
        val script = buildString {
            appendLine("uid=$uid")
            appendLine("src_bound=${escapeShellSingleQuoted(srcBound)}")
            appendLine("bound=${escapeShellSingleQuoted(bound)}")
            appendLine("fail=0")
            appendLine(syncRegularFile)
            pairs.forEach { (src, dst) ->
                if (src.any { it == '\n' || it == '\r' || it == '\u0000' } ||
                    dst.any { it == '\n' || it == '\r' || it == '\u0000' }
                ) {
                    appendLine("fail=1")
                    return@forEach
                }
                append("sync_one ")
                append(escapeShellSingleQuoted(src))
                append(' ')
                append(escapeShellSingleQuoted(dst))
                appendLine()
            }
            appendLine("exit ${'$'}fail")
        }
        return runRootScriptHeredoc(script, timeoutSeconds = 120) == 0
    }

    // POSIX sh 没有局部变量，辅助函数的临时名必须带前缀，否则会盖掉 sync_one 的 parent。
    private val syncRegularFile = """
        read_context() {
          rc_path=${'$'}1
          if [ -L "${'$'}rc_path" ]; then
            return 1
          fi
          rc_ctx=${'$'}(stat -c %C "${'$'}rc_path" 2>/dev/null) || rc_ctx=
          rc_ctx=${'$'}{rc_ctx%%[[:space:]]*}
          case "${'$'}rc_ctx" in
            ""|"?"|unlabeled|*[!A-Za-z0-9_:.,-]*) rc_ctx= ;;
            *:*:*) ;;
            *) rc_ctx= ;;
          esac
          if [ -z "${'$'}rc_ctx" ]; then
            rc_line=${'$'}(ls -dZ "${'$'}rc_path" 2>/dev/null) || rc_line=
            for rc_field in ${'$'}rc_line; do
              case "${'$'}rc_field" in
                *[!A-Za-z0-9_:.,-]*) continue ;;
                *:*:*) rc_ctx=${'$'}rc_field; break ;;
              esac
            done
          fi
          case "${'$'}rc_ctx" in
            *:*:*) printf '%s\n' "${'$'}rc_ctx" ;;
            *) return 1 ;;
          esac
        }
        selinux_off() {
          se_mode=${'$'}(getenforce 2>/dev/null) || return 1
          case "${'$'}se_mode" in
            Disabled|disabled) return 0 ;;
            *) return 1 ;;
          esac
        }
        ensure_bound_ctx() {
          if [ -n "${'$'}bound_ctx_state" ]; then
            return 0
          fi
          if [ -L "${'$'}bound" ] || [ ! -d "${'$'}bound" ]; then
            bound_ctx_state=bad
            return 0
          fi
          bound_ctx=${'$'}(read_context "${'$'}bound") || bound_ctx=
          if [ -n "${'$'}bound_ctx" ]; then
            bound_ctx_state=ok
          elif selinux_off; then
            bound_ctx_state=off
          else
            bound_ctx_state=bad
          fi
        }
        # 不写全局 fail。目录重标失败不能拦住其它文件；文件发布只看返回值。
        label_as_bound() {
          lb_target=${'$'}1
          if [ -L "${'$'}lb_target" ]; then
            return 1
          fi
          guard "${'$'}bound" "${'$'}lb_target" || return 1
          ensure_bound_ctx
          case "${'$'}bound_ctx_state" in
            off) return 0 ;;
            ok) ;;
            *) return 1 ;;
          esac
          # chcon 失败也读回。已经是目标上下文时不要当成失败，否则已标好的目录会挡住回写。
          chcon -h "${'$'}bound_ctx" "${'$'}lb_target" 2>/dev/null || true
          lb_now=${'$'}(read_context "${'$'}lb_target") || return 1
          [ "${'$'}lb_now" = "${'$'}bound_ctx" ]
        }
        # 返回非 0 只表示这个父目录 app 用不了。调用方放弃该文件，不写全局 fail。
        own_parents() {
          op_dir=${'$'}1
          while [ "${'$'}op_dir" != "${'$'}bound" ]; do
            case "${'$'}op_dir" in
              "${'$'}bound"/*) ;;
              *) return 1 ;;
            esac
            if [ -L "${'$'}op_dir" ] || [ ! -d "${'$'}op_dir" ]; then
              return 1
            fi
            phys_inside "${'$'}bound" "${'$'}op_dir" || return 1
            chown -h "${'$'}uid:${'$'}uid" "${'$'}op_dir" || return 1
            label_as_bound "${'$'}op_dir" || return 1
            op_dir=${'$'}(dirname "${'$'}op_dir")
          done
          return 0
        }
        reject_dots() {
          rd_root=${'$'}1
          rd_cur=${'$'}2
          while [ "${'$'}rd_cur" != "${'$'}rd_root" ]; do
            rd_base=${'$'}(basename "${'$'}rd_cur")
            case "${'$'}rd_base" in
              ""|.|..) return 1 ;;
            esac
            rd_parent=${'$'}(dirname "${'$'}rd_cur")
            if [ "${'$'}rd_parent" = "${'$'}rd_cur" ]; then
              return 1
            fi
            rd_cur=${'$'}rd_parent
          done
          return 0
        }
        reject_links() {
          rl_root=${'$'}1
          rl_cur=${'$'}2
          while :; do
            if [ -L "${'$'}rl_cur" ]; then
              return 1
            fi
            if [ "${'$'}rl_cur" = "${'$'}rl_root" ]; then
              return 0
            fi
            rl_parent=${'$'}(dirname "${'$'}rl_cur")
            if [ "${'$'}rl_parent" = "${'$'}rl_cur" ]; then
              return 1
            fi
            rl_cur=${'$'}rl_parent
          done
        }
        phys_inside() {
          pi_root=${'$'}1
          pi_cur=${'$'}2
          pi_root_real=${'$'}(readlink -f "${'$'}pi_root") || return 1
          while [ ! -e "${'$'}pi_cur" ] && [ ! -L "${'$'}pi_cur" ]; do
            pi_parent=${'$'}(dirname "${'$'}pi_cur")
            if [ "${'$'}pi_parent" = "${'$'}pi_cur" ]; then
              return 1
            fi
            pi_cur=${'$'}pi_parent
          done
          if [ -L "${'$'}pi_cur" ]; then
            return 1
          fi
          pi_real=${'$'}(readlink -f "${'$'}pi_cur") || return 1
          case "${'$'}pi_real" in
            "${'$'}pi_root_real"|"${'$'}pi_root_real"/*) return 0 ;;
            *) return 1 ;;
          esac
        }
        guard() {
          g_root=${'$'}1
          g_path=${'$'}2
          case "${'$'}g_path" in
            "${'$'}g_root"|"${'$'}g_root"/*) ;;
            *) return 1 ;;
          esac
          reject_dots "${'$'}g_root" "${'$'}g_path" || return 1
          reject_links "${'$'}g_root" "${'$'}g_path" || return 1
          phys_inside "${'$'}g_root" "${'$'}g_path" || return 1
        }
        parse_mtime() {
          mt_stamp=${'$'}1
          case "${'$'}mt_stamp" in
            [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]" "[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9]*) ;;
            *) return 1 ;;
          esac
          _sec=${'$'}{mt_stamp%%.*}
          mt_rest=${'$'}{mt_stamp#*.}
          _nsec=${'$'}{mt_rest%%[!0-9]*}
          case "${'$'}_nsec" in
            [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]) ;;
            *) return 1 ;;
          esac
          return 0
        }
        dst_not_older() {
          mt_src=${'$'}1
          mt_dst=${'$'}2
          src_y=${'$'}(stat -c %y "${'$'}mt_src" 2>/dev/null) || return 2
          dst_y=${'$'}(stat -c %y "${'$'}mt_dst" 2>/dev/null) || return 2
          parse_mtime "${'$'}src_y" || return 2
          src_sec=${'$'}_sec
          src_nsec=${'$'}_nsec
          parse_mtime "${'$'}dst_y" || return 2
          dst_sec=${'$'}_sec
          dst_nsec=${'$'}_nsec
          if [ "${'$'}dst_sec" = "${'$'}src_sec" ] && [ "${'$'}dst_nsec" = "${'$'}src_nsec" ]; then
            cmp -s "${'$'}mt_src" "${'$'}mt_dst"
            cmp_rc=${'$'}?
            if [ "${'$'}cmp_rc" -eq 0 ]; then
              return 0
            fi
            if [ "${'$'}cmp_rc" -eq 1 ]; then
              return 1
            fi
            return 2
          fi
          if [ "${'$'}dst_sec" != "${'$'}src_sec" ]; then
            first=${'$'}(printf '%s\n%s\n' "${'$'}dst_sec" "${'$'}src_sec" | LC_ALL=C sort | head -n 1)
            if [ "${'$'}first" = "${'$'}dst_sec" ]; then
              return 1
            fi
            return 0
          fi
          if [ "${'$'}dst_nsec" -gt "${'$'}src_nsec" ]; then
            return 0
          fi
          return 1
        }
        sync_one() {
          src=${'$'}1
          dst=${'$'}2
          case "${'$'}dst" in
            "${'$'}bound"/*) ;;
            *) fail=1; return 0 ;;
          esac
          case "${'$'}src" in
            "${'$'}src_bound"/*) ;;
            *) fail=1; return 0 ;;
          esac
          if [ -L "${'$'}src" ] || [ ! -f "${'$'}src" ] || [ -L "${'$'}dst" ] || [ -d "${'$'}dst" ]; then
            return 0
          fi
          if [ -f "${'$'}dst" ]; then
            dst_not_older "${'$'}src" "${'$'}dst"
            rc=${'$'}?
            if [ "${'$'}rc" -eq 0 ]; then
              return 0
            fi
            if [ "${'$'}rc" -eq 2 ]; then
              fail=1
              return 0
            fi
          fi
          guard "${'$'}src_bound" "${'$'}src" || { fail=1; return 0; }
          guard "${'$'}bound" "${'$'}dst" || { fail=1; return 0; }
          parent=${'$'}(dirname "${'$'}dst")
          guard "${'$'}bound" "${'$'}parent" || { fail=1; return 0; }
          mkdir -p "${'$'}parent" || { fail=1; return 0; }
          guard "${'$'}bound" "${'$'}parent" || { fail=1; return 0; }
          own_parents "${'$'}parent" || { fail=1; return 0; }
          if [ -L "${'$'}src" ] || [ ! -f "${'$'}src" ] || ! guard "${'$'}src_bound" "${'$'}src"; then
            fail=1
            return 0
          fi
          if [ -L "${'$'}dst" ] || [ -d "${'$'}dst" ] || ! guard "${'$'}bound" "${'$'}dst"; then
            fail=1
            return 0
          fi
          tmp="${'$'}dst.tmp.${'$'}${'$'}"
          rm -f "${'$'}tmp"
          cp -P "${'$'}src" "${'$'}tmp" || { fail=1; rm -f "${'$'}tmp"; return 0; }
          if [ -L "${'$'}tmp" ]; then
            fail=1
            rm -f "${'$'}tmp"
            return 0
          fi
          chown -h "${'$'}uid:${'$'}uid" "${'$'}tmp" || { fail=1; rm -f "${'$'}tmp"; return 0; }
          chmod 0644 "${'$'}tmp" || { fail=1; rm -f "${'$'}tmp"; return 0; }
          touch -r "${'$'}src" "${'$'}tmp" || fail=1
          # 只看这次标注的返回值。全局 fail 含 touch -r 和其它文件，不能拿来决定是否 mv。
          label_as_bound "${'$'}tmp" || { fail=1; rm -f "${'$'}tmp"; return 0; }
          # cp 到 mv 之间祖先可能被换成链接。这里的 mv 才会把文件写出 bound。
          if [ -L "${'$'}dst" ] || [ -d "${'$'}dst" ] || ! guard "${'$'}bound" "${'$'}dst" || ! guard "${'$'}bound" "${'$'}tmp"; then
            fail=1
            if guard "${'$'}bound" "${'$'}tmp"; then
              rm -f "${'$'}tmp"
            fi
            return 0
          fi
          mv -f "${'$'}tmp" "${'$'}dst" || { fail=1; rm -f "${'$'}tmp"; return 0; }
        }
    """.trimIndent()

}
