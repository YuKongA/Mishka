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
        val command = "cd \"$workDir\" && \"$binary\" $argsStr > \"$logFile\" 2>&1 & echo \$!"
        Log.i(TAG, "以 root 启动: su -c \"$command\"")
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val reader = process.inputStream.bufferedReader()
            val pidLine = reader.readLine()?.trim() ?: ""
            val pid = pidLine.toIntOrNull() ?: -1
            Log.i(TAG, "mihomo 实际 PID: $pid")
            process.inputStream.close()
            pid
        } catch (e: Exception) {
            Log.e(TAG, "以 root 启动失败: ${e.message}")
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

    fun killAsRoot(pid: Int) {
        try {
            Log.i(TAG, "以 root 终止进程: pid=$pid")
            val process = ProcessBuilder("su", "-c", "kill $pid")
                .redirectErrorStream(true)
                .start()
            process.waitFor(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "以 root 终止进程失败: ${e.message}")
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
