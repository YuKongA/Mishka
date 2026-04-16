package top.yukonga.mishka.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.mishka.R
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

    fun attachToExisting(pid: Int, secret: String, subscriptionId: String?): Boolean {
        if (pid <= 0 || !RootHelper.isAliveAsRoot(pid)) return false
        childPid = pid
        isRootMode = true
        this.secret = secret
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

            if (useRoot) {
                val logFile = File(workDir, "mihomo.log").absolutePath
                childPid = RootHelper.startAsRoot(binary.absolutePath, args, workDir.absolutePath, logFile)
            } else {
                childPid = ProcessHelper.nativeForkExec(
                    binary.absolutePath,
                    args,
                    workDir.absolutePath,
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
                childPid = -1
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
                val killed = RootHelper.killAsRoot(childPid)
                if (!killed) {
                    RootHelper.killMihomoByName()
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
                return if (useRoot) {
                    val logFile = File(workDir, "mihomo.log").absolutePath
                    val logContent = RootHelper.readLogFile(logFile)
                    if (logContent.isNotBlank()) {
                        Log.e(TAG, "mihomo log:\n$logContent")
                        context.getString(R.string.error_mihomo_start_failed, logContent)
                    } else {
                        context.getString(R.string.error_mihomo_exited)
                    }
                } else {
                    context.getString(R.string.error_mihomo_exited)
                }
            }
            // API 响应则就绪
            if (isApiReady()) return null
        }
        return context.getString(R.string.error_api_not_ready)
    }

    private fun isApiReady(): Boolean {
        return try {
            val conn = URL("http://$externalController/version").openConnection() as HttpURLConnection
            conn.connectTimeout = 500
            conn.readTimeout = 500
            conn.setRequestProperty("Authorization", "Bearer $secret")
            val code = conn.responseCode
            conn.disconnect()
            code == 200
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
