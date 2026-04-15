package top.yukonga.mishka.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.mishka.R
import java.io.File

class MihomoRunner(private val context: Context) {

    private var childPid: Int = -1
    private var isRootMode = false
    val pid: Int get() = childPid
    var secret: String = ""
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

        binary.setExecutable(true)

        isRootMode = useRoot
        activeSubscriptionId = subscriptionId
        val configFile = ConfigGenerator.getConfigFile(context)

        val workDir = if (subscriptionId != null) {
            ProfileFileOps.getSubscriptionDir(context, subscriptionId)
        } else {
            ConfigGenerator.getWorkDir(context)
        }

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

            delay(2000)

            val started = if (useRoot) RootHelper.isAliveAsRoot(childPid) else isProcessAlive(childPid)
            if (!started) {
                if (useRoot) {
                    val logFile = File(workDir, "mihomo.log").absolutePath
                    val logContent = RootHelper.readLogFile(logFile)
                    errorMessage = if (logContent.isNotBlank()) {
                        Log.e(TAG, "mihomo log:\n$logContent")
                        context.getString(R.string.error_mihomo_start_failed, logContent)
                    } else {
                        context.getString(R.string.error_mihomo_exited)
                    }
                } else {
                    errorMessage = context.getString(R.string.error_mihomo_exited)
                }
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
                RootHelper.killAsRoot(childPid)
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
