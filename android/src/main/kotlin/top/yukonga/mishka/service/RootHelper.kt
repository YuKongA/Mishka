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

    fun cleanupOrphanedMihomo() {
        try {
            val process = ProcessBuilder("su", "-c", "pkill -f libmihomo.so 2>/dev/null; true")
                .redirectErrorStream(true)
                .start()
            process.waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
    }
}
