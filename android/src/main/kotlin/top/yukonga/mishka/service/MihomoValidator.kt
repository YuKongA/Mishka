package top.yukonga.mishka.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 使用 mihomo -t 进行完整配置校验。
 * 与 MihomoRunner 不同，不需要 TUN fd 继承，可安全使用 ProcessBuilder。
 */
object MihomoValidator {

    private const val TAG = "MihomoValidator"
    private const val TIMEOUT_MS = 90_000L

    /**
     * 对指定工作目录运行 mihomo -t 校验配置。
     *
     * @param context Android 上下文
     * @param workDir 工作目录（包含 config.yaml 和 providers/）
     * @return null 表示校验通过，否则返回错误信息
     */
    suspend fun validate(
        context: Context, workDir: String, onProgress: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        val binary = getMihomoBinary(context)
        if (binary == null) {
            Log.e(TAG, "mihomo binary not found")
            return@withContext "mihomo binary not found"
        }

        binary.setExecutable(true)

        val configFile = File(workDir, "config.yaml")
        if (!configFile.exists()) {
            return@withContext "Configuration file does not exist: ${configFile.absolutePath}"
        }

        try {
            val process = ProcessBuilder(
                binary.absolutePath,
                "-t",
                "-d", workDir,
                "-f", configFile.absolutePath,
            )
                .directory(File(workDir))
                .redirectErrorStream(true)
                .start()

            val outputBuilder = StringBuilder()
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    outputBuilder.appendLine(line)
                    if (onProgress != null) {
                        parseProgressLine(line)?.let { onProgress(it) }
                    }
                }
            }
            val output = outputBuilder.toString()

            val exited = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!exited) {
                process.destroyForcibly()
                Log.e(TAG, "mihomo -t timed out")
                return@withContext "mihomo -t timed out"
            }

            val exitCode = process.exitValue()
            Log.i(TAG, "mihomo -t exit=$exitCode, output:\n$output")

            if (exitCode == 0) {
                null
            } else {
                parseErrorMessage(output)
            }
        } catch (e: Exception) {
            Log.e(TAG, "mihomo -t failed", e)
            "mihomo -t failed: ${e.message}"
        }
    }

    /**
     * 从 mihomo 输出中提取错误信息。
     * mihomo 日志格式: time="..." level=error msg="..."
     */
    private fun parseErrorMessage(output: String): String {
        // 检查 GeoIP 下载超时
        if (output.contains("can't download MMDB") || output.contains("can't download GeoIP") ||
            output.contains("context deadline exceeded") && output.contains("GeoIP")
        ) {
            return "can't download GeoIP"
        }

        // 查找 level=error 或 level=fatal 的行
        val errorLines = output.lines().filter { line ->
            line.contains("level=error") || line.contains("level=fatal")
        }

        if (errorLines.isNotEmpty()) {
            // 提取 msg="..." 内容
            val msgRegex = Regex("""msg="(.+?)"""")
            val messages = errorLines.mapNotNull { line ->
                msgRegex.find(line)?.groupValues?.get(1)
            }
            if (messages.isNotEmpty()) {
                return "Invalid configuration: ${messages.joinToString("; ")}"
            }
        }

        // 如果没有标准格式的错误信息，返回最后几行
        val lastLines = output.lines().filter { it.isNotBlank() }.takeLast(3)
        return if (lastLines.isNotEmpty()) {
            "Invalid configuration: ${lastLines.joinToString(" ")}"
        } else {
            "Configuration verification failed (unknown error)"
        }
    }

    private val providerRegex = Regex("""msg=".*?(?:provider|Provider)\s+(.+?)[\s"]""")

    /**
     * 从 mihomo 日志行中提取进度信息。
     * 例如: time="..." level=info msg="Start initial provider xxx"
     */
    private fun parseProgressLine(line: String): String? {
        if (line.contains("level=error") || line.contains("level=fatal")) return null
        val match = providerRegex.find(line)
        if (match != null) return match.groupValues[1]
        return null
    }

    private fun getMihomoBinary(context: Context): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val binary = File(nativeDir, "libmihomo.so")
        return if (binary.exists()) binary else null
    }
}
