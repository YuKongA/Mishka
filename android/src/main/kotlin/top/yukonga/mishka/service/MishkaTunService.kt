package top.yukonga.mishka.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.mishka.R
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyServiceStatus
import top.yukonga.mishka.platform.ProxyState
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.TunMode
import java.io.File
import java.io.FileDescriptor

@SuppressLint("VpnServicePolicy")
class MishkaTunService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runner by lazy { MihomoRunner(this) }
    private val dynamicNotification by lazy { DynamicNotificationManager(this, scope) }
    private var tunFd: Int = -1
    private var monitorJob: Job? = null
    private var notificationRefreshJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        try {
            startForeground(
                NotificationHelper.NOTIFICATION_ID_VPN,
                NotificationHelper.buildLoadingNotification(this),
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            ProxyServiceBridge.updateState(
                ProxyServiceStatus(
                    ProxyState.Error,
                    errorMessage = getString(R.string.error_foreground_failed, e.message ?: e.javaClass.simpleName),
                    tunMode = TunMode.Vpn,
                )
            )
            stopSelf()
            return
        }
        // 监听动态通知设置变化，实时切换通知样式
        notificationRefreshJob = scope.launch {
            ProxyServiceBridge.notificationRefresh.collect {
                val state = ProxyServiceBridge.state.value
                if (state.state == ProxyState.Running && state.tunMode == TunMode.Vpn) {
                    dynamicNotification.stop()
                    dynamicNotification.startOrFallbackStatic(
                        PlatformStorage(this@MishkaTunService),
                        state.secret,
                        state.externalController,
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val subscriptionId = intent.getStringExtra(EXTRA_SUBSCRIPTION_ID)
                startProxy(subscriptionId)
            }
            ACTION_STOP -> stopProxy()
            ACTION_RESTART -> {
                val subscriptionId = intent.getStringExtra(EXTRA_SUBSCRIPTION_ID)
                restartProxy(subscriptionId)
            }
        }
        return START_STICKY
    }

    @SuppressLint("NewApi")
    private fun startProxy(subscriptionId: String? = null) {
        scope.launch {
            Log.i(TAG, "Starting proxy, subscription: $subscriptionId")
            ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Starting, tunMode = TunMode.Vpn))

            // 清理当前实例 runner 的残留
            if (runner.isRunning) {
                runner.stop()
            }

            // 清理孤儿 mihomo（setsid 脱离进程组后 App 崩溃也不会带走 mihomo，需 pkill）
            // 条件 hadRootPid || hasRoot：后者覆盖"ROOT 崩溃后 storage 被清但进程仍活"的冷启动；
            // 无 root 设备两者均为 false，不触发 su。
            val storage = PlatformStorage(this@MishkaTunService)
            val hadRootPid = storage.getString(StorageKeys.ROOT_MIHOMO_PID, "").isNotEmpty()
            val hasRoot = storage.getString(StorageKeys.HAS_ROOT, "false") == "true"
            if (hadRootPid || hasRoot) {
                RootHelper.cleanupOrphanedMihomo()
                storage.putString(StorageKeys.ROOT_MIHOMO_PID, "")
                storage.putString(StorageKeys.ROOT_MIHOMO_SECRET, "")
                storage.putString(StorageKeys.ROOT_ACTIVE_SUBSCRIPTION_ID, "")
            }

            // 1. 建立 VPN 接口，获取 fd
            val fd = try {
                Builder().apply {
                    addAddress(TUN_GATEWAY, TUN_SUBNET_PREFIX)
                    setMtu(TUN_MTU)
                    setSession("Mishka")
                    setBlocking(false)

                    // VPN 设置：绕过私有网络
                    val bypassPrivate = storage.getString(StorageKeys.VPN_BYPASS_PRIVATE_NETWORK, "true") == "true"
                    if (bypassPrivate) {
                        resources.getStringArray(R.array.bypass_private_route).forEach { cidr ->
                            val parts = cidr.split("/")
                            addRoute(parts[0], parts[1].toInt())
                        }
                        addRoute(TUN_DNS, 32)
                    } else {
                        addRoute("0.0.0.0", 0)
                    }

                    // VPN 设置：允许 IPv6
                    val allowIpv6 = storage.getString(StorageKeys.VPN_ALLOW_IPV6, "false") == "true"
                    if (allowIpv6) {
                        addAddress(TUN_GATEWAY6, TUN_SUBNET_PREFIX6)
                        if (bypassPrivate) {
                            resources.getStringArray(R.array.bypass_private_route6).forEach { cidr ->
                                val parts = cidr.split("/")
                                addRoute(parts[0], parts[1].toInt())
                            }
                            addRoute(TUN_DNS6, 128)
                        } else {
                            addRoute("::", 0)
                        }
                    }

                    // VPN 设置：DNS 劫持
                    val dnsHijacking = storage.getString(StorageKeys.VPN_DNS_HIJACKING, "true") == "true"
                    if (dnsHijacking) {
                        addDnsServer(TUN_DNS)
                        if (allowIpv6) addDnsServer(TUN_DNS6)
                    }

                    // VPN 设置：允许应用绕过
                    val allowBypass = storage.getString(StorageKeys.VPN_ALLOW_BYPASS, "true") == "true"
                    if (allowBypass) {
                        allowBypass()
                    }

                    // 应用代理设置
                    val proxyMode = storage.getString(StorageKeys.APP_PROXY_MODE, "AllowAll")
                    val packages = storage.getStringSet(StorageKeys.APP_PROXY_PACKAGES, emptySet())

                    when (proxyMode) {
                        "AllowSelected" -> {
                            addAllowedApplication(packageName)
                            packages.forEach { pkg ->
                                if (pkg != packageName) {
                                    try { addAllowedApplication(pkg) } catch (_: Exception) {}
                                }
                            }
                        }
                        "DenySelected" -> {
                            addDisallowedApplication(packageName)
                            packages.forEach { pkg ->
                                if (pkg != packageName) {
                                    try { addDisallowedApplication(pkg) } catch (_: Exception) {}
                                }
                            }
                        }
                        else -> {
                            addDisallowedApplication(packageName)
                        }
                    }

                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        setMetered(false)

                        // VPN 设置：系统代理
                        val systemProxy = storage.getString(StorageKeys.VPN_SYSTEM_PROXY, "true") == "true"
                        if (systemProxy) {
                            val port = storage.getString("override_mixed_port", "null")
                                .toIntOrNull() ?: 7890
                            setHttpProxy(
                                android.net.ProxyInfo.buildDirectProxy(
                                    "127.0.0.1",
                                    port,
                                    listOf("localhost", "*.local", "127.*", "10.*", "172.16.*",
                                        "172.17.*", "172.18.*", "172.19.*", "172.20.*",
                                        "172.21.*", "172.22.*", "172.23.*", "172.24.*",
                                        "172.25.*", "172.26.*", "172.27.*", "172.28.*",
                                        "172.29.*", "172.30.*", "172.31.*", "192.168.*"),
                                )
                            )
                        }
                    }
                }.establish()?.detachFd()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to establish VPN", e)
                ProxyServiceBridge.updateState(
                    ProxyServiceStatus(ProxyState.Error, errorMessage = getString(R.string.error_vpn_failed, e.message ?: ""))
                )
                stopSelf()
                return@launch
            }

            if (fd == null || fd < 0) {
                Log.e(TAG, "VPN establish returned null (permission denied?)")
                ProxyServiceBridge.updateState(
                    ProxyServiceStatus(ProxyState.Error, errorMessage = getString(R.string.error_vpn_denied))
                )
                stopSelf()
                return@launch
            }

            tunFd = fd
            Log.i(TAG, "VPN established, fd=$fd")

            // 清除 O_CLOEXEC 标志，使 fd 能被子进程（mihomo）继承
            // Android 默认给 fd 设置 O_CLOEXEC，fork+exec 时会关闭，导致 mihomo 拿不到 fd
            try {
                val pfd = ParcelFileDescriptor.adoptFd(fd)
                val flags = Os.fcntlInt(pfd.fileDescriptor, OsConstants.F_GETFD, 0)
                Log.i(TAG, "fd=$fd flags before: $flags")
                Os.fcntlInt(pfd.fileDescriptor, OsConstants.F_SETFD, flags and OsConstants.FD_CLOEXEC.inv())
                val flagsAfter = Os.fcntlInt(pfd.fileDescriptor, OsConstants.F_GETFD, 0)
                Log.i(TAG, "fd=$fd flags after: $flagsAfter")
                pfd.detachFd() // 释放所有权，防止 GC 关闭 fd
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear O_CLOEXEC on fd=$fd: $e")
            }

            // 2. 生成配置（注入 file-descriptor）
            val result = ConfigGenerator.writeRunConfig(this@MishkaTunService, ConfigGenerator.generateSecret(), subscriptionId, tunFd = fd)
            runner.secret = result.secret
            runner.externalController = result.externalController

            // 3. 启动 mihomo 核心
            val success = runner.start(subscriptionId)
            if (!success) {
                val errorMsg = runner.errorMessage.ifBlank { getString(R.string.error_start_failed) }
                Log.e(TAG, "Failed to start mihomo: $errorMsg")
                ProxyServiceBridge.updateState(
                    ProxyServiceStatus(ProxyState.Error, errorMessage = errorMsg)
                )
                closeTunFd()
                stopSelf()
                return@launch
            }

            // 4. 更新通知和状态
            ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Running, secret = runner.secret, externalController = result.externalController, tunMode = TunMode.Vpn, startTime = System.currentTimeMillis(), mihomoPid = runner.pid))

            dynamicNotification.startOrFallbackStatic(storage, runner.secret, result.externalController)
            // 记录运行状态，用于开机自启判断
            PlatformStorage(this@MishkaTunService).putString(StorageKeys.SERVICE_WAS_RUNNING, "true")
            Log.i(TAG, "Proxy running, fd=$fd")

            // 5. 启动进程存活监测
            val monitorWorkDir = if (subscriptionId != null) {
                ProfileFileOps.getSubscriptionDir(this@MishkaTunService, subscriptionId)
            } else {
                ConfigGenerator.getWorkDir(this@MishkaTunService)
            }
            startProcessMonitor(monitorWorkDir)
        }
    }

    @SuppressLint("StringFormatInvalid")
    private fun startProcessMonitor(workDir: File) {
        monitorJob?.cancel()
        monitorJob = scope.launch(Dispatchers.IO) {
            // 等待一段时间再开始监测，避免与 waitForReady 重叠
            delay(10_000)
            while (runner.isRunning) {
                delay(5_000)
            }
            // 进程异常退出
            val logFile = File(workDir, "mihomo.log")
            val logContent = if (logFile.exists()) logFile.readText().trim().lines().takeLast(10).joinToString("\n") else ""
            val errorMsg = if (logContent.isNotBlank()) {
                getString(R.string.error_mihomo_start_failed, logContent)
            } else {
                getString(R.string.error_mihomo_exited)
            }
            Log.e(TAG, "mihomo process died unexpectedly: $errorMsg")
            ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Error, errorMessage = errorMsg))
            closeTunFd()
            PlatformStorage(this@MishkaTunService).putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun restartProxy(subscriptionId: String?) {
        Log.i(TAG, "Restarting proxy...")
        monitorJob?.cancel()
        ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopping, tunMode = TunMode.Vpn))
        dynamicNotification.stop()
        scope.launch(Dispatchers.IO) {
            runner.stop()
            closeTunFd()
            withContext(Dispatchers.Main) {
                startProxy(subscriptionId)
            }
        }
    }

    private fun stopProxy() {
        Log.i(TAG, "Stopping proxy...")
        monitorJob?.cancel()
        ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopping, tunMode = TunMode.Vpn))
        dynamicNotification.stop()
        scope.launch(Dispatchers.IO) {
            runner.stop()
            closeTunFd()
            PlatformStorage(this@MishkaTunService).putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
            ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopped))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun closeTunFd() {
        if (tunFd >= 0) {
            try {
                val fileDescriptor = FileDescriptor()
                val field = FileDescriptor::class.java.getDeclaredField("descriptor")
                field.isAccessible = true
                field.setInt(fileDescriptor, tunFd)
                Os.close(fileDescriptor)
                Log.i(TAG, "Closed tun fd=$tunFd")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close tun fd=$tunFd: $e")
            }
            tunFd = -1
        }
    }

    override fun onDestroy() {
        notificationRefreshJob?.cancel()
        monitorJob?.cancel()
        dynamicNotification.stop()
        runner.stop()
        closeTunFd()
        PlatformStorage(this).putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
        ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopped))
        scope.cancel()
        Log.i(TAG, "MishkaTunService destroyed")
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked by system")
        monitorJob?.cancel()
        ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopping, tunMode = TunMode.Vpn))
        dynamicNotification.stop()
        PlatformStorage(this).putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
        scope.launch(Dispatchers.IO) {
            runner.stop()
            closeTunFd()
            ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopped))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    companion object {
        private const val TAG = "MishkaTunService"
        const val ACTION_START = "top.yukonga.mishka.START"
        const val ACTION_STOP = "top.yukonga.mishka.STOP"
        const val ACTION_RESTART = "top.yukonga.mishka.RESTART"
        const val EXTRA_SUBSCRIPTION_ID = "subscription_id"

        private const val TUN_MTU = 9000
        private const val TUN_SUBNET_PREFIX = 30
        private const val TUN_GATEWAY = "198.18.0.1"
        private const val TUN_GATEWAY6 = "fdfe:dcba:9876::1"
        private const val TUN_SUBNET_PREFIX6 = 126
        private const val TUN_DNS = "198.18.0.2"
        private const val TUN_DNS6 = "fdfe:dcba:9876::2"

        fun start(context: Context, subscriptionId: String? = null) {
            val intent = Intent(context, MishkaTunService::class.java).apply {
                action = ACTION_START
                subscriptionId?.let { putExtra(EXTRA_SUBSCRIPTION_ID, it) }
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MishkaTunService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
