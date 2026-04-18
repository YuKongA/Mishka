package top.yukonga.mishka.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 使用 mihomo -prefetch 预下载所有 HTTP provider 到本地磁盘。
 *
 * 目的是规避 mihomo 启动瞬间（TUN/DNS bring-up 0.5s 内）HTTP provider 并发拉取
 * 被 TCP/TLS 瞬态错误（EOF / "io: read/write on closed pipe"）打断，导致代理组
 * `include-all + filter` 从 0 节点里 filter 出 0 个的问题。
 *
 * best-effort：子进程退出码非 0 仅记日志，不抛异常；单个 provider 失败在 mihomo
 * 内部就被 log.Warnln 吞掉，退出码保持 0。
 */
object MihomoPrefetcher {

    private const val TAG = "MihomoPrefetcher"
    private const val TIMEOUT_MS = 120_000L

    /**
     * 对工作目录下的 config.yaml 做 provider 预下载。
     *
     * @return true 表示子进程正常完成；false 表示进程未找到 / 启动失败 / 超时
     */
    suspend fun prefetch(
        context: Context,
        workDir: String,
        configFileName: String = "config.yaml",
        onProgress: ((String) -> Unit)? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val binary = MihomoValidator.getMihomoBinary(context)
        if (binary == null) {
            Log.w(TAG, "mihomo binary not found, skip prefetch")
            return@withContext false
        }
        binary.setExecutable(true)

        val configFile = File(workDir, configFileName)
        if (!configFile.exists()) {
            Log.w(TAG, "config file does not exist, skip prefetch: ${configFile.absolutePath}")
            return@withContext false
        }

        val process = try {
            ProcessBuilder(
                binary.absolutePath,
                "-prefetch",
                "-d", workDir,
                "-f", configFile.absolutePath,
            )
                .directory(File(workDir))
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            Log.e(TAG, "mihomo -prefetch failed to start", e)
            return@withContext false
        }

        // stdout 消费必须独立线程：provider HTTP 读无 deadline 时 mihomo 会长时间不刷新输出，
        // 在主协程里 readLine 会阻塞到进程自然 EOF，让后面的 waitFor(timeout) 形同虚设。
        val outputBuilder = StringBuilder()
        val readerThread = Thread({
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        synchronized(outputBuilder) { outputBuilder.appendLine(line) }
                        if (onProgress != null) {
                            parseProgressLine(line)?.let { onProgress(it) }
                        }
                    }
                }
            } catch (_: Exception) {
                // destroyForcibly 后 pipe 关闭，read 抛异常属预期
            }
        }, "mihomo-prefetch-reader").apply {
            isDaemon = true
            start()
        }

        return@withContext try {
            val exited = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!exited) {
                process.destroyForcibly()
                readerThread.join(1000)
                Log.w(TAG, "mihomo -prefetch timed out after ${TIMEOUT_MS}ms, output so far:\n${snapshotOutput(outputBuilder)}")
                false
            } else {
                readerThread.join(1000)
                val exitCode = process.exitValue()
                Log.i(TAG, "mihomo -prefetch exit=$exitCode, output:\n${snapshotOutput(outputBuilder)}")
                exitCode == 0
            }
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            readerThread.join(500)
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun snapshotOutput(sb: StringBuilder): String = synchronized(sb) { sb.toString() }

    // mihomo 成功日志格式: time="..." level=info msg="prefetch proxy provider <name> done"
    private val doneRegex = Regex("""msg="prefetch (?:proxy|rule) provider (.+?) done"""")

    private fun parseProgressLine(line: String): String? {
        return doneRegex.find(line)?.groupValues?.get(1)
    }
}
