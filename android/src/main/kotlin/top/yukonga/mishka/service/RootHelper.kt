package top.yukonga.mishka.service

import android.util.Log
import java.util.concurrent.TimeUnit

object RootHelper {

    private const val TAG = "RootHelper"

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
     */
    fun startAsRoot(binary: String, args: Array<String>, workDir: String, logFile: String): Int {
        val argsStr = args.joinToString(" ") { "\"$it\"" }
        val command = "cd \"$workDir\" || exit 1; \"$binary\" $argsStr > \"$logFile\" 2>&1 & echo \$!"
        Log.i(TAG, "Starting as root: su -c \"$command\"")
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
            val process = ProcessBuilder("su", "-c", "tail -n $maxLines \"$logFile\" 2>/dev/null")
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
            // SIGTERM
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
            Log.w(TAG, "Falling back to pkill for libmihomo.so")
            runRootCommand("pkill -TERM -f libmihomo.so")
            Thread.sleep(1000)
            runRootCommand("pkill -9 -f libmihomo.so")
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
            runRootCommand("ip link delete $tunDevice 2>/dev/null; true")
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
            pgrep -f libmihomo.so >/dev/null 2>&1 && {
                pkill -TERM -f libmihomo.so 2>/dev/null
                i=0; while [ ${'$'}i -lt 6 ]; do
                    sleep 0.5
                    pgrep -f libmihomo.so >/dev/null 2>&1 || break
                    i=${'$'}((i+1))
                done
                pgrep -f libmihomo.so >/dev/null 2>&1 && {
                    pkill -KILL -f libmihomo.so 2>/dev/null
                    i=0; while [ ${'$'}i -lt 4 ]; do
                        sleep 0.5
                        pgrep -f libmihomo.so >/dev/null 2>&1 || break
                        i=${'$'}((i+1))
                    done
                }
            }
            # 进程清理后（或本就不存在孤儿），兜底清 TUN 避免下次启动 EEXIST
            $tunCleanupLine
            pgrep -f libmihomo.so >/dev/null 2>&1 && exit 1
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
     * POSIX shell 单引号转义：外层用单引号包裹，内部单引号替换为 `'\''`。
     * 防止用户可配置的 device name 注入命令（Settings 已有正则约束，此处是 defense in depth）。
     */
    private fun escapeShellSingleQuoted(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"

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
}
