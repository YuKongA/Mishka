package top.yukonga.mishka.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.mishka.R
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MihomoRunner(private val context: Context) {

    private var childPid: Int = -1
    private var isRootMode = false
    val pid: Int get() = childPid
    var secret: String = ""
    var externalController: String = "127.0.0.1:9090"
    var activeSubscriptionId: String? = null
        private set
    var errorMessage: String = ""
        private set

    val isRunning: Boolean
        get() = childPid > 0 && if (isRootMode) RootHelper.isAliveAsRoot(childPid) else isProcessAlive(childPid)

    /**
     * 尝试重连已持久化的 mihomo 进程。三重校验任一失败均返回 false：
     *   1. PID 存活（kill -0）
     *   2. cmdline 含 libmihomo.so（防 PID 复用撞其他 root 进程）
     *   3. stored secret 能通过 /configs 鉴权（防 secret 漂移导致 UI 假 Running）
     * 校验阶段**不**修改任何 field，全部通过后再原子赋值。
     *
     * TODO: 后续还应校验 persisted subscriptionId 与请求的 subscriptionId 是否一致，
     *       不一致时应 kill + 全新启动。本次修复暂不覆盖该场景。
     */
    fun attachToExisting(
        pid: Int,
        secret: String,
        externalController: String,
        subscriptionId: String?,
    ): Boolean {
        if (pid <= 0 || !RootHelper.isAliveAsRoot(pid)) {
            Log.w(TAG, "Attach failed: pid dead (pid=$pid)")
            return false
        }
        val cmdline = RootHelper.readRootCmdline(pid)
        if (!cmdline.contains("libmihomo.so")) {
            Log.w(TAG, "Attach failed: wrong cmdline (pid=$pid, cmdline=${cmdline.take(64)})")
            return false
        }
        if (!isApiAuthorized(secret, externalController)) {
            Log.w(TAG, "Attach failed: auth failed (pid=$pid)")
            return false
        }
        // 全部通过，原子赋值
        childPid = pid
        isRootMode = true
        this.secret = secret
        this.externalController = externalController
        activeSubscriptionId = subscriptionId
        Log.i(TAG, "Attached to existing mihomo process: pid=$pid")
        return true
    }

    suspend fun start(subscriptionId: String? = null, useRoot: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (isRunning) {
            Log.w(TAG, "mihomo already running")
            return@withContext true
        }

        val binary = getMihomoBinary() ?: run {
            errorMessage = context.getString(R.string.error_mihomo_not_found)
            Log.e(TAG, errorMessage)
            return@withContext false
        }

        isRootMode = useRoot
        activeSubscriptionId = subscriptionId
        val configFile = ConfigGenerator.getConfigFile(context)

        val workDir = if (subscriptionId != null) {
            ProfileFileOps.getSubscriptionDir(context, subscriptionId)
        } else {
            ConfigGenerator.getWorkDir(context)
        }

        ProfileFileOps.ensureGeodataLinks(context, workDir)

        try {
            val args = arrayOf(
                "-d", workDir.absolutePath,
                "-f", configFile.absolutePath,
            )
            val logFile = File(workDir, "mihomo.log")

            if (useRoot) {
                childPid = RootHelper.startAsRoot(binary.absolutePath, args, workDir.absolutePath, logFile.absolutePath)
            } else {
                childPid = ProcessHelper.nativeForkExec(
                    binary.absolutePath,
                    args,
                    workDir.absolutePath,
                    logFile.absolutePath,
                )
            }

            if (childPid <= 0) {
                errorMessage = if (useRoot) context.getString(R.string.error_root_start_failed) else context.getString(R.string.error_fork_failed)
                Log.e(TAG, errorMessage)
                return@withContext false
            }

            Log.i(TAG, "mihomo child pid=$childPid (root=$useRoot)")

            // 轮询 API 确认 mihomo 真正就绪，进程退出则快速失败
            val result = waitForReady(useRoot, workDir)
            if (result != null) {
                errorMessage = result
                Log.e(TAG, errorMessage)
                stop() // kill 子进程 + waitpid 回收，防止孤儿进程占用端口
                return@withContext false
            }

            errorMessage = ""
            Log.i(TAG, "mihomo started: pid=$childPid (root=$useRoot)")
            true
        } catch (e: Exception) {
            errorMessage = context.getString(R.string.error_generic_start_failed, e.message ?: "")
            Log.e(TAG, "Failed to start mihomo", e)
            false
        }
    }

    fun stop() {
        if (childPid > 0) {
            Log.i(TAG, "Stopping mihomo pid=$childPid (root=$isRootMode)")
            if (isRootMode) {
                val tunDevice = PlatformStorage(context).getString(StorageKeys.ROOT_TUN_DEVICE, "Mishka")
                val killed = RootHelper.killAsRoot(childPid, tunDevice)
                if (!killed) {
                    RootHelper.killMihomoByName(tunDevice)
                }
            } else {
                ProcessHelper.nativeKill(childPid)
                try {
                    ProcessHelper.nativeWaitpid(childPid)
                } catch (_: Exception) {
                }
            }
            childPid = -1
        }
        secret = ""
        isRootMode = false
    }

    /**
     * 轮询等待 mihomo 就绪。返回 null 表示成功，返回错误消息表示失败。
     * 每轮先检查进程是否存活（快速失败），再尝试 API 连接。
     */
    private suspend fun waitForReady(useRoot: Boolean, workDir: File): String? {
        repeat(20) {
            delay(500)
            // 进程退出则快速失败
            val alive = if (useRoot) RootHelper.isAliveAsRoot(childPid) else isProcessAlive(childPid)
            if (!alive) {
                val logContent = readStartupLog(useRoot, workDir)
                return if (logContent.isNotBlank()) {
                    Log.e(TAG, "mihomo log:\n$logContent")
                    context.getString(R.string.error_mihomo_start_failed, extractErrorMessage(logContent))
                } else {
                    context.getString(R.string.error_mihomo_exited)
                }
            }
            // API 响应则就绪
            if (isApiReady()) return null
        }
        // 超时：尝试读取日志辅助诊断
        val logContent = readStartupLog(useRoot, workDir)
        return if (logContent.isNotBlank()) {
            Log.w(TAG, "API timeout, mihomo log:\n$logContent")
            context.getString(R.string.error_api_not_ready) + "\n" + extractErrorMessage(logContent)
        } else {
            context.getString(R.string.error_api_not_ready)
        }
    }

    /** 统一读取 mihomo 启动日志 */
    private fun readStartupLog(useRoot: Boolean, workDir: File): String {
        return if (useRoot) {
            RootHelper.readLogFile(File(workDir, "mihomo.log").absolutePath)
        } else {
            val logFile = File(workDir, "mihomo.log")
            if (logFile.exists()) {
                logFile.readText().trim().lines().takeLast(20).joinToString("\n")
            } else ""
        }
    }

    /** 从日志中提取 level=error/fatal 的错误消息 */
    private fun extractErrorMessage(logContent: String): String {
        val errorLines = logContent.lines().filter {
            it.contains("level=error") || it.contains("level=fatal")
        }
        if (errorLines.isEmpty()) return logContent.lines().takeLast(5).joinToString("\n")

        val msgRegex = Regex("""msg="(.+?)"""")
        val messages = errorLines.mapNotNull { msgRegex.find(it)?.groupValues?.get(1) }
        return if (messages.isNotEmpty()) messages.joinToString("\n") else errorLines.joinToString("\n")
    }

    private fun isApiReady(): Boolean {
        return try {
            val conn = URL("http://$externalController/version").openConnection() as HttpURLConnection
            conn.connectTimeout = 500
            conn.readTimeout = 500
            conn.responseCode // 任何响应（200/401 等）都说明 API 已就绪
            conn.disconnect()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 带 Bearer secret 鉴权地探测 /configs（/configs 在 mihomo 各版本均强制鉴权，
     * /version 在部分版本免鉴权不可靠）。仅 2xx 视为鉴权通过。
     */
    private fun isApiAuthorized(secret: String, externalController: String, timeoutMs: Int = 1500): Boolean {
        return try {
            val conn = URL("http://$externalController/configs").openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Authorization", "Bearer $secret")
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    private fun isProcessAlive(pid: Int): Boolean {
        return try {
            File("/proc/$pid").exists()
        } catch (_: Exception) {
            false
        }
    }

    private fun getMihomoBinary(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val binary = File(nativeDir, "libmihomo.so")
        if (binary.exists()) return binary
        return null
    }

    companion object {
        private const val TAG = "MihomoRunner"
    }
}
