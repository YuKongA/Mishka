package top.yukonga.mishka.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import top.yukonga.mishka.R
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.SystemDnsObserver
import top.yukonga.mishka.util.describe
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class MihomoRunner(private val context: Context) {

    // Service 串行管理 start/stop；此锁额外隔离 VPN monitor 与 stop，不包住 ROOT 的 su 调用。
    private val processLock = Any()

    @Volatile
    private var childPid: Int = -1

    @Volatile
    private var isRootMode = false
    private var vpnReadyFile: File? = null
    val pid: Int get() = synchronized(processLock) { childPid }
    var secret: String = ""
    var externalController: String = "127.0.0.1:9090"
    var activeSubscriptionId: String? = null
        private set
    var errorMessage: String = ""
        private set

    /**
     * 进程是否仍在。VPN 走 waitid(WNOWAIT)，只观察、由 stop 统一收割；
     * ROOT 每次调用都要 fork 一个 su——`/proc` 在 Android 10+ 是 hidepid，
     * app 读不到 root 进程，轮询它的地方要自己控频。
     */
    val isRunning: Boolean
        get() {
            val rootPid = synchronized(processLock) {
                if (!isRootMode) return childPid > 0 && isProcessAlive(childPid)
                childPid
            }
            return rootPid > 0 && RootHelper.isAliveAsRoot(rootPid)
        }

    /**
     * 尝试重连已持久化的 mihomo 进程。PID、进程名、controller 鉴权与透明 DNS
     * 必须同时有效；旧核心只有 API 可用不代表 DNS 已接管。
     * 校验阶段不修改任何 field，全部通过后才认领进程。
     * 订阅一致性由调用方在此之前校验。
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
        if (!cmdline.contains("libmihomo_runner.so")) {
            Log.w(TAG, "Attach failed: wrong cmdline (pid=$pid, cmdline=${cmdline.take(64)})")
            return false
        }
        if (!RootHelper.hasCoreCredentials(pid)) {
            Log.w(TAG, "Attach failed: incompatible ROOT process credentials (pid=$pid)")
            return false
        }
        if (!isApiAuthorized(secret, externalController) || !isRootDnsReady(secret, externalController)) {
            Log.w(TAG, "Attach failed: controller or ROOT DNS is not ready (pid=$pid)")
            return false
        }
        childPid = pid
        isRootMode = true
        this.secret = secret
        this.externalController = externalController
        activeSubscriptionId = subscriptionId
        Log.i(TAG, "Attached to existing mihomo process: pid=$pid")
        return true
    }

    /**
     * 停机前认领 app 进程死亡后残留的 root 进程。本实例从未 start 也没 attach 过（attach-only
     * 只由重新打开 app 触发，外部 intent 不经过），childPid 为空会让 [stop] 整段跳过、进程杀不掉。
     *
     * 只校验 PID 存活与 cmdline，不查 API 授权：停机不需要进程响应 API，而残留进程的授权状态
     * 可能已经不可用。cmdline 校验用于挡住 PID 复用后误杀无关进程。
     */
    fun adoptForStop(pid: Int): Boolean {
        if (childPid > 0) return true
        if (pid <= 0 || !RootHelper.isAliveAsRoot(pid)) return false
        if (!RootHelper.readRootCmdline(pid).contains("libmihomo_runner.so")) return false
        childPid = pid
        isRootMode = true
        Log.i(TAG, "Adopted persisted mihomo pid=$pid for stop")
        return true
    }

    suspend fun start(
        subscriptionId: String? = null,
        useRoot: Boolean = false,
        overrideJsonPath: String,
        secret: String,
        externalController: String,
        ageSecretKey: String = "",
        transformPath: String? = null,
        preferTransformMixedPort: Boolean = false,
        vpnGateway: String? = null,
        vpnHttpFd: Int = -1,
        vpnPlatformSocket: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
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
        this@MihomoRunner.secret = secret
        this@MihomoRunner.externalController = externalController

        // ROOT 模式走独立 runtime/{uuid}/ 沙箱（mihomo 会以 uid=0 写 provider 缓存，不能污染 imported/；停机由 RootRuntimeCache 回写后再删）；
        // 调用方（MishkaRootService）必须在 start 之前调 ProfileFileOps.prepareRootRuntime 准备好内容。
        val workDir = when {
            useRoot && subscriptionId != null -> ProfileFileOps.getRuntimeDir(context, subscriptionId)
            subscriptionId != null -> ProfileFileOps.getSubscriptionDir(context, subscriptionId)
            else -> ConfigGenerator.getWorkDir(context)
        }
        // 订阅原文 config.yaml 作为主配置；override 通过 --override-json 在 mihomo 内存中注入，
        // 订阅 YAML 全程不被 Kotlin 改写
        val configFile = File(workDir, "config.yaml")

        // ROOT 模式的 geodata 链接已在 prepareRootRuntime 内建立；VPN 模式首启时需要建立
        if (!useRoot) {
            ProfileFileOps.ensureGeodataLinks(context, workDir)
        }

        try {
            val readyFile = if (!useRoot) {
                require(!vpnGateway.isNullOrEmpty() && !vpnPlatformSocket.isNullOrEmpty())
                File(context.cacheDir, "vpn-${UUID.randomUUID()}.ready").also { vpnReadyFile = it }
            } else null
            // age 加密订阅：config.yaml 加密落盘，运行时用 --age-secret-key 让 mihomo 加载时解密
            val args = buildList {
                add("-d"); add(workDir.absolutePath)
                add("-f"); add(configFile.absolutePath)
                add("--override-json"); add(overrideJsonPath)
                add("--system-dns"); add(SystemDnsObserver(context).snapshot().joinToString(","))
                if (!useRoot) {
                    add("--vpn-gateway"); add(requireNotNull(vpnGateway))
                    add("--vpn-platform-socket"); add(requireNotNull(vpnPlatformSocket))
                    add("--vpn-ready-file"); add(requireNotNull(readyFile).absolutePath)
                    if (vpnHttpFd >= 0) {
                        add("--vpn-http-fd"); add(vpnHttpFd.toString())
                    }
                } else {
                    add("--root-dns")
                }
                if (transformPath != null) {
                    add("--transform"); add(transformPath)
                    if (preferTransformMixedPort) add("--prefer-transform-mixed-port")
                }
                add("--secret"); add(secret)
                add("--ext-ctl"); add(externalController)
                if (ageSecretKey.isNotEmpty()) {
                    add("--age-secret-key"); add(ageSecretKey)
                }
            }.toTypedArray()
            val logFile = File(workDir, "mihomo.log")
            if (!useRoot) currentCoroutineContext().ensureActive()

            childPid = if (useRoot) {
                RootHelper.startAsRoot(
                    binary.absolutePath,
                    args,
                    workDir.absolutePath,
                    logFile.absolutePath
                )
            } else {
                ProcessHelper.nativeForkExec(
                    binary.absolutePath,
                    args,
                    workDir.absolutePath,
                    logFile.absolutePath,
                )
            }

            if (childPid <= 0) {
                errorMessage =
                    if (useRoot) context.getString(R.string.error_root_start_failed) else context.getString(R.string.error_fork_failed)
                Log.e(TAG, errorMessage)
                return@withContext false
            }

            Log.i(TAG, "mihomo child pid=$childPid (root=$useRoot)")
            if (useRoot && !RootHelper.hasCoreCredentials(childPid)) {
                errorMessage = context.getString(
                    R.string.error_generic_start_failed,
                    "ROOT process must run as 0:${RootHelper.CORE_GID}",
                )
                stop()
                return@withContext false
            }

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
        } catch (e: CancellationException) {
            stop()
            throw e
        } catch (e: Exception) {
            stop()
            errorMessage = context.getString(R.string.error_generic_start_failed, e.describe())
            Log.e(TAG, "Failed to start mihomo", e)
            false
        }
    }

    /** 返回是否确认进程已死：未确认时调用方必须保留持久化 PID，否则残留进程再也定位不到。 */
    fun stop(): Boolean = if (isRootMode) {
        stopOwnedProcess()
    } else {
        synchronized(processLock) { stopOwnedProcess() }
    }

    private fun stopOwnedProcess(): Boolean {
        var confirmedDead = true
        if (childPid > 0) {
            val target = childPid
            Log.i(TAG, "Stopping mihomo pid=$target (root=$isRootMode)")
            if (isRootMode) {
                val tunDevice = PlatformStorage(context).getString(StorageKeys.ROOT_TUN_DEVICE, "Mishka")
                val killed = RootHelper.killAsRoot(target, tunDevice)
                if (!killed) {
                    RootHelper.killMihomoByName(tunDevice)
                }
                // pkill 兜底后不复查的话，杀掉与否无从得知
                confirmedDead = !RootHelper.isAliveAsRoot(target)
            } else {
                val result = ProcessHelper.nativeStop(target, GRACEFUL_STOP_TIMEOUT_MS, FORCE_STOP_TIMEOUT_MS)
                confirmedDead = result >= 0 || result == ProcessHelper.STOP_NOT_CHILD
                if (!confirmedDead) {
                    // 未确认死亡时保留所有权，调用方据此保留持久化 PID
                    Log.w(
                        TAG,
                        if (result == ProcessHelper.STOP_TIMEOUT) {
                            "mihomo pid=$target did not exit after SIGKILL; process ownership retained"
                        } else {
                            "Failed to stop mihomo pid=$target (errno=${ProcessHelper.STOP_ERROR_BASE - result}); process ownership retained"
                        },
                    )
                }
            }
            if (!confirmedDead) return false
            childPid = -1
        }
        secret = ""
        isRootMode = false
        vpnReadyFile?.delete()
        vpnReadyFile = null
        return true
    }

    /**
     * 轮询等待 mihomo 就绪。返回 null 表示成功，返回错误消息表示失败。
     * VPN 等原生的成功确认文件和带鉴权 API；ROOT 另需透明 DNS 入口就绪。
     */
    private suspend fun waitForReady(useRoot: Boolean, workDir: File): String? {
        var rootRound = 0
        var vpnApiChecks = 0
        while (!useRoot || rootRound < 20) {
            delay(500)
            val checkLiveness = !useRoot || rootRound++ % LIVENESS_CHECK_EVERY == 0
            if (useRoot) {
                if (isRootDnsReady(secret, externalController, timeoutMs = 500)) {
                    delay(500)
                    return scanLogForTunError(true, workDir)
                }
            } else {
                // provider 初始化不占用 API 探测预算，原生 ready 后才开始计数。
                val ready = vpnReadyFile?.takeIf { it.isFile }?.readText() == "ready"
                if (ready) {
                    if (isApiAuthorized(secret, externalController, timeoutMs = 500)) return null
                    if (++vpnApiChecks >= 20) break
                }
            }
            if (!checkLiveness) continue
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
        }
        // 超时：尝试读取日志辅助诊断
        val timeoutMessage = context.getString(if (useRoot) R.string.error_api_not_ready else R.string.error_vpn_not_ready)
        val logContent = readStartupLog(useRoot, workDir)
        return if (logContent.isNotBlank()) {
            Log.w(TAG, "API timeout, mihomo log:\n$logContent")
            timeoutMessage + "\n" + extractErrorMessage(logContent)
        } else {
            timeoutMessage
        }
    }

    /**
     * 扫描 mihomo 启动日志，识别 TUN inbound 初始化失败。
     * 与 mihomo listener.ReCreateTun / PatchInboundListeners 的 log.Errorln 对齐：
     *   - "Start TUN listening error"（listener.go:497-534 ReCreateTun 主路径）
     *   - "configure tun interface"（sing_tun/server.go TUN 创建失败）
     *   - "create NetworkUpdateMonitor"（netlink 监听器创建失败）
     * 固定模式比泛匹配更精准，误报率低。
     */
    private fun scanLogForTunError(useRoot: Boolean, workDir: File): String? {
        val log = readStartupLog(useRoot, workDir)
        if (log.isBlank()) return null
        val tunErrorPatterns = listOf(
            "Start TUN listening error",
            "configure tun interface",
            "create NetworkUpdateMonitor",
        )
        val errorLine = log.lines().firstOrNull { line ->
            (line.contains("level=error") || line.contains("level=fatal")) &&
                    tunErrorPatterns.any { line.contains(it, ignoreCase = true) }
        } ?: return null
        Log.e(TAG, "TUN init failed: $errorLine")
        return context.getString(R.string.error_tun_init_failed, extractErrorMessage(errorLine))
    }

    /**
     * 统一读取 mihomo 启动日志。
     * 取 200 行足以容纳 Go panic stack（通常 30-50 行）+ panic 前的 log.Errorln / stderr 输出，
     * 避免"panic stack 占满 20 行" 把真正的 first error 挤出来。
     */
    private fun readStartupLog(useRoot: Boolean, workDir: File): String {
        return if (useRoot) {
            RootHelper.readLogFile(File(workDir, "mihomo.log").absolutePath)
        } else {
            File(workDir, "mihomo.log").readLastLines(STARTUP_LOG_LINES)
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

    private fun isRootDnsReady(secret: String, externalController: String, timeoutMs: Int = 1500): Boolean {
        return try {
            val conn = URL("http://$externalController/dns/root").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.setRequestProperty("Authorization", "Bearer $secret")
                conn.responseCode == HttpURLConnection.HTTP_NO_CONTENT
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isProcessAlive(pid: Int): Boolean = ProcessHelper.nativeIsAlive(pid)

    private fun getMihomoBinary(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val binary = File(nativeDir, "libmihomo_runner.so")
        if (binary.exists()) return binary
        return null
    }

    companion object {
        private const val TAG = "MihomoRunner"

        /** waitForReady 每几轮（每轮 500ms）做一次判活 */
        private const val LIVENESS_CHECK_EVERY = 4

        private const val GRACEFUL_STOP_TIMEOUT_MS = 3000
        private const val FORCE_STOP_TIMEOUT_MS = 500

        /** 足以容纳 Go panic stack（30-50 行）+ panic 前的 first error，不被 stack 挤掉 */
        private const val STARTUP_LOG_LINES = 200
    }
}
