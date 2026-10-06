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
     * 单次 su 把 root 写入的普通文件拷回 [bound] 下。跳过符号链接和更新的目标；
     * 结果 chown 到 [uid]、chmod 0644，并用 bound 的 SELinux 标签标记，避免 imported/ 留下
     * root:root 或 app 读不回的文件。保留源 mtime。中间新建目录同样改属主和标签。
     * 任一文件失败返回 false，已成功的不回滚。
     */
    fun syncRegularFiles(uid: Int, bound: String, pairs: List<Pair<String, String>>): Boolean {
        if (pairs.isEmpty() || uid <= 0) return pairs.isEmpty()
        if (bound.isEmpty() || bound.any { it == '\n' || it == '\r' || it == '\u0000' }) return false
        val script = buildString {
            appendLine("uid=$uid")
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

    private val syncRegularFile = """
        label_as_bound() {
          target=${'$'}1
          if chcon --reference="${'$'}bound" "${'$'}target" 2>/dev/null; then
            return 0
          fi
          if command -v restorecon >/dev/null 2>&1; then
            restorecon -F "${'$'}target" 2>/dev/null && return 0
            restorecon "${'$'}target" 2>/dev/null && return 0
          fi
          fail=1
          return 0
        }
        own_parents() {
          dir=${'$'}1
          while [ "${'$'}dir" != "${'$'}bound" ]; do
            case "${'$'}dir" in
              "${'$'}bound"/*) ;;
              *) fail=1; return 0 ;;
            esac
            chown "${'$'}uid:${'$'}uid" "${'$'}dir" || { fail=1; return 0; }
            label_as_bound "${'$'}dir"
            dir=${'$'}(dirname "${'$'}dir")
          done
        }
        sync_one() {
          src=${'$'}1
          dst=${'$'}2
          case "${'$'}dst" in
            "${'$'}bound"/*) ;;
            *) fail=1; return 0 ;;
          esac
          if [ -L "${'$'}src" ] || [ ! -f "${'$'}src" ] || [ -L "${'$'}dst" ] || [ -d "${'$'}dst" ]; then
            return 0
          fi
          if [ -f "${'$'}dst" ]; then
            src_m=${'$'}(stat -c %Y "${'$'}src" 2>/dev/null) || src_m=
            dst_m=${'$'}(stat -c %Y "${'$'}dst" 2>/dev/null) || dst_m=
            if [ -n "${'$'}src_m" ] && [ -n "${'$'}dst_m" ] && [ "${'$'}dst_m" -ge "${'$'}src_m" ]; then
              return 0
            fi
          fi
          parent=${'$'}(dirname "${'$'}dst")
          mkdir -p "${'$'}parent" || { fail=1; return 0; }
          own_parents "${'$'}parent"
          tmp="${'$'}dst.tmp.${'$'}${'$'}"
          rm -f "${'$'}tmp"
          cp "${'$'}src" "${'$'}tmp" || { fail=1; rm -f "${'$'}tmp"; return 0; }
          chown "${'$'}uid:${'$'}uid" "${'$'}tmp" || { fail=1; rm -f "${'$'}tmp"; return 0; }
          chmod 0644 "${'$'}tmp" || { fail=1; rm -f "${'$'}tmp"; return 0; }
          label_as_bound "${'$'}tmp"
          touch -r "${'$'}src" "${'$'}tmp" || fail=1
          mv -f "${'$'}tmp" "${'$'}dst" || { fail=1; rm -f "${'$'}tmp"; return 0; }
        }
    """.trimIndent()

}
