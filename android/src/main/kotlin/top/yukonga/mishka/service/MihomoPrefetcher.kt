package top.yukonga.mishka.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

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
    private const val TIMEOUT_DIRECT_MS = 120_000L
    private const val TIMEOUT_PROXY_MS = 60_000L
    private const val POLL_INTERVAL_MS = 200L

    /**
     * 对工作目录下的 config.yaml 做 provider 预下载。
     *
     * @param proxyUrl 非空时设置 `HTTPS_PROXY` / `HTTP_PROXY` 环境变量；走代理时超时缩短至 60s
     *                 （代理链路本应比直连更快，60s 仍没完大概率是节点链路问题，
     *                  宁可提前放弃让 mihomo 运行期的 pullLoop 兜底重试）。
     * @return true 表示子进程正常完成；false 表示进程未找到 / 启动失败 / 超时 / 非 0 退出
     */
    suspend fun prefetch(
        context: Context,
        workDir: String,
        configFileName: String = "config.yaml",
        proxyUrl: String? = null,
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

        val pb = ProcessBuilder(
            binary.absolutePath,
            "-prefetch",
            "-d", workDir,
            "-f", configFile.absolutePath,
        )
            .directory(File(workDir))
            .redirectErrorStream(true)

        if (proxyUrl != null) {
            // Go 的 net/http.DefaultTransport 读取这两个环境变量作为 HTTP proxy。
            pb.environment()["HTTPS_PROXY"] = proxyUrl
            pb.environment()["HTTP_PROXY"] = proxyUrl
        }

        val process = try {
            pb.start()
        } catch (e: Exception) {
            Log.e(TAG, "mihomo -prefetch failed to start", e)
            return@withContext false
        }

        val timeoutMs = if (proxyUrl != null) TIMEOUT_PROXY_MS else TIMEOUT_DIRECT_MS

        // stdout 消费必须独立线程：provider HTTP 读无 deadline 时 mihomo 会长时间不刷新输出，
        // 在主协程里 readLine 会阻塞到进程自然 EOF，让后面的轮询循环形同虚设。
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

        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                coroutineContext.ensureActive()
                if (!process.isAlive) break
                if (System.currentTimeMillis() > deadline) {
                    Log.w(TAG, "mihomo -prefetch timed out after ${timeoutMs}ms (proxy=${proxyUrl != null}), output so far:\n${snapshotOutput(outputBuilder)}")
                    return@withContext false
                }
                delay(POLL_INTERVAL_MS)
            }
            readerThread.join(1000)
            val exitCode = process.exitValue()
            Log.i(TAG, "mihomo -prefetch exit=$exitCode (proxy=${proxyUrl != null}), output:\n${snapshotOutput(outputBuilder)}")
            return@withContext exitCode == 0
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                readerThread.join(500)
            }
        }
    }

    private fun snapshotOutput(sb: StringBuilder): String = synchronized(sb) { sb.toString() }

    // mihomo 成功日志格式: time="..." level=info msg="prefetch proxy provider <name> done"
    private val doneRegex = Regex("""msg="prefetch (?:proxy|rule) provider (.+?) done"""")

    private fun parseProgressLine(line: String): String? {
        return doneRegex.find(line)?.groupValues?.get(1)
    }
}
