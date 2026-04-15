package top.yukonga.mishka.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 使用 mihomo -t 进行完整配置校验。
 * 与 MihomoRunner 不同，不需要 TUN fd 继承，可安全使用 ProcessBuilder。
 */
object MihomoValidator {

    private const val TAG = "MihomoValidator"
    private const val TIMEOUT_MS = 30_000L

    /**
     * 对指定工作目录运行 mihomo -t 校验配置。
     *
     * @param context Android 上下文
     * @param workDir 工作目录（包含 config.yaml 和 providers/）
     * @return null 表示校验通过，否则返回错误信息
     */
    suspend fun validate(context: Context, workDir: String): String? = withContext(Dispatchers.IO) {
        val binary = getMihomoBinary(context)
        if (binary == null) {
            Log.e(TAG, "mihomo binary not found")
            return@withContext "mihomo 二进制文件未找到"
        }

        binary.setExecutable(true)

        val configFile = File(workDir, "config.yaml")
        if (!configFile.exists()) {
            return@withContext "配置文件不存在: ${configFile.absolutePath}"
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

            val output = process.inputStream.bufferedReader().use { it.readText() }

            val exited = process.waitFor(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!exited) {
                process.destroyForcibly()
                Log.e(TAG, "mihomo -t timed out")
                return@withContext "配置校验超时"
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
            "配置校验失败: ${e.message}"
        }
    }

    /**
     * 从 mihomo 输出中提取错误信息。
     * mihomo 日志格式: time="..." level=error msg="..."
     */
    private fun parseErrorMessage(output: String): String {
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
                return "配置无效: ${messages.joinToString("; ")}"
            }
        }

        // 如果没有标准格式的错误信息，返回最后几行
        val lastLines = output.lines().filter { it.isNotBlank() }.takeLast(3)
        return if (lastLines.isNotEmpty()) {
            "配置无效: ${lastLines.joinToString(" ")}"
        } else {
            "配置校验失败（未知错误）"
        }
    }

    private fun getMihomoBinary(context: Context): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val binary = File(nativeDir, "libmihomo.so")
        return if (binary.exists()) binary else null
    }
}
