package top.yukonga.mishka.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class MihomoRunner(private val context: Context) {

    private var childPid: Int = -1
    var secret: String = ""
    var activeSubscriptionId: String? = null
        private set
    var errorMessage: String = ""
        private set

    val isRunning: Boolean get() = childPid > 0 && isProcessAlive(childPid)

    suspend fun start(subscriptionId: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (isRunning) {
            Log.w(TAG, "mihomo already running")
            return@withContext true
        }

        val binary = getMihomoBinary() ?: run {
            errorMessage = "mihomo 二进制文件未找到"
            Log.e(TAG, errorMessage)
            return@withContext false
        }

        binary.setExecutable(true)

        activeSubscriptionId = subscriptionId
        val configFile = ConfigGenerator.getConfigFile(context)

        val workDir = if (subscriptionId != null) {
            ProfileFileOps.getSubscriptionDir(context, subscriptionId)
        } else {
            ConfigGenerator.getWorkDir(context)
        }

        try {
            // 使用 JNI fork+exec，不关闭继承的 fd（包括 VPN TUN fd）
            val args = arrayOf(
                "-d", workDir.absolutePath,
                "-f", configFile.absolutePath,
            )
            childPid = ProcessHelper.nativeForkExec(
                binary.absolutePath,
                args,
                workDir.absolutePath,
            )

            if (childPid <= 0) {
                errorMessage = "fork+exec 失败"
                Log.e(TAG, errorMessage)
                return@withContext false
            }

            Log.i(TAG, "mihomo child pid=$childPid")

            // 通过 /proc/<pid>/fd/1 读取子进程 stdout（因为 JNI fork 没有 pipe）
            // 改为等待一段时间后检查进程是否存活
            Thread.sleep(2000)

            val started = isProcessAlive(childPid)
            if (!started) {
                errorMessage = "mihomo 进程启动后立即退出"
                Log.e(TAG, errorMessage)
                childPid = -1
                return@withContext false
            }

            errorMessage = ""
            Log.i(TAG, "mihomo started: pid=$childPid")
            true
        } catch (e: Exception) {
            errorMessage = "启动失败: ${e.message}"
            Log.e(TAG, "Failed to start mihomo", e)
            false
        }
    }

    fun stop() {
        if (childPid > 0) {
            Log.i(TAG, "Stopping mihomo pid=$childPid")
            ProcessHelper.nativeKill(childPid)
            try {
                ProcessHelper.nativeWaitpid(childPid)
            } catch (_: Exception) {
            }
            childPid = -1
        }
        secret = ""
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
        Log.d(TAG, "Checking nativeLibraryDir: ${binary.absolutePath}, exists=${binary.exists()}")
        if (binary.exists()) return binary

        val nativeDirFile = File(nativeDir)
        if (nativeDirFile.exists()) {
            Log.d(TAG, "nativeLibraryDir contents: ${nativeDirFile.listFiles()?.map { it.name }}")
        }

        return null
    }

    companion object {
        private const val TAG = "MihomoRunner"
    }
}
