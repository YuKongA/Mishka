package top.yukonga.mishka.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import top.yukonga.mishka.R
import top.yukonga.mishka.data.api.MihomoApiClient
import top.yukonga.mishka.data.api.MihomoWebSocket
import top.yukonga.mishka.util.FormatUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyServiceStatus
import top.yukonga.mishka.platform.ProxyState

class MishkaTunService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runner by lazy { MihomoRunner(this) }
    private var tunFd: Int = -1
    private var trafficJob: Job? = null
    private var screenReceiver: BroadcastReceiver? = null

    inner class LocalBinder : Binder() {
        val service: MishkaTunService get() = this@MishkaTunService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        startForeground(
            NotificationHelper.NOTIFICATION_ID_VALUE,
            NotificationHelper.buildLoadingNotification(this),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val subscriptionId = intent.getStringExtra(EXTRA_SUBSCRIPTION_ID)
                startProxy(subscriptionId)
            }
            ACTION_STOP -> stopProxy()
        }
        return START_STICKY
    }

    private fun startProxy(subscriptionId: String? = null) {
        scope.launch {
            Log.i(TAG, "Starting proxy, subscription: $subscriptionId")
            ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Starting))

            // 1. 建立 VPN 接口，获取 fd
            val storage = PlatformStorage(this@MishkaTunService)
            val fd = try {
                Builder().apply {
                    addAddress(TUN_GATEWAY, TUN_SUBNET_PREFIX)
                    setMtu(TUN_MTU)
                    setSession("Mishka")
                    setBlocking(false)

                    // VPN 设置：绕过私有网络
                    val bypassPrivate = storage.getString("vpn_bypass_private_network", "true") == "true"
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
                    val allowIpv6 = storage.getString("vpn_allow_ipv6", "false") == "true"
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
                    val dnsHijacking = storage.getString("vpn_dns_hijacking", "true") == "true"
                    if (dnsHijacking) {
                        addDnsServer(TUN_DNS)
                        if (allowIpv6) addDnsServer(TUN_DNS6)
                    }

                    // VPN 设置：允许应用绕过
                    val allowBypass = storage.getString("vpn_allow_bypass", "true") == "true"
                    if (allowBypass) {
                        allowBypass()
                    }

                    // 应用代理设置
                    val proxyMode = storage.getString("app_proxy_mode", "AllowAll")
                    val packages = storage.getStringSet("app_proxy_packages", emptySet())

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
                        val systemProxy = storage.getString("vpn_system_proxy", "true") == "true"
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
                    ProxyServiceStatus(ProxyState.Error, errorMessage = "VPN 建立失败: ${e.message}")
                )
                stopSelf()
                return@launch
            }

            if (fd == null || fd < 0) {
                Log.e(TAG, "VPN establish returned null (permission denied?)")
                ProxyServiceBridge.updateState(
                    ProxyServiceStatus(ProxyState.Error, errorMessage = "VPN 权限被拒绝")
                )
                stopSelf()
                return@launch
            }

            tunFd = fd
            Log.i(TAG, "VPN established, fd=$fd")

            // 清除 O_CLOEXEC 标志，使 fd 能被子进程（mihomo）继承
            // Android 默认给 fd 设置 O_CLOEXEC，fork+exec 时会关闭，导致 mihomo 拿不到 fd
            try {
                val pfd = android.os.ParcelFileDescriptor.adoptFd(fd)
                val flags = android.system.Os.fcntlInt(pfd.fileDescriptor, android.system.OsConstants.F_GETFD, 0)
                Log.i(TAG, "fd=$fd flags before: $flags")
                android.system.Os.fcntlInt(pfd.fileDescriptor, android.system.OsConstants.F_SETFD, flags and android.system.OsConstants.FD_CLOEXEC.inv())
                val flagsAfter = android.system.Os.fcntlInt(pfd.fileDescriptor, android.system.OsConstants.F_GETFD, 0)
                Log.i(TAG, "fd=$fd flags after: $flagsAfter")
                pfd.detachFd() // 释放所有权，防止 GC 关闭 fd
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear O_CLOEXEC on fd=$fd: $e")
            }

            // 2. 生成配置（注入 file-descriptor）
            val secret = ConfigGenerator.generateSecret()
            runner.secret = secret
            ConfigGenerator.writeRunConfig(this@MishkaTunService, secret, subscriptionId, tunFd = fd)

            // 3. 启动 mihomo 核心
            val success = runner.start(subscriptionId)
            if (!success) {
                val errorMsg = runner.errorMessage.ifBlank { "启动失败" }
                Log.e(TAG, "Failed to start mihomo: $errorMsg")
                ProxyServiceBridge.updateState(
                    ProxyServiceStatus(ProxyState.Error, errorMessage = errorMsg)
                )
                closeTunFd()
                stopSelf()
                return@launch
            }

            // 4. 更新通知和状态
            ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Running, secret = runner.secret))

            val isDynamic = storage.getString("dynamic_notification", "true") == "true"
            if (isDynamic) {
                startDynamicNotification(storage, runner.secret)
            } else {
                val notification = NotificationHelper.buildRunningNotification(this@MishkaTunService)
                getSystemService(NotificationManager::class.java)
                    ?.notify(NotificationHelper.NOTIFICATION_ID_VPN, notification)
            }
            // 记录运行状态，用于开机自启判断
            PlatformStorage(this@MishkaTunService).putString("service_was_running", "true")
            Log.i(TAG, "Proxy running, fd=$fd")
        }
    }

    private fun startDynamicNotification(storage: PlatformStorage, secret: String) {
        val profileName = storage.getString("active_profile_name", "Mishka")
        val notificationManager = getSystemService(NotificationManager::class.java)
        val apiClient = MihomoApiClient(secret = secret)
        val webSocket = MihomoWebSocket(apiClient)

        var isScreenOn = getSystemService(PowerManager::class.java)?.isInteractive ?: true

        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                isScreenOn = intent?.action == Intent.ACTION_SCREEN_ON
            }
        }
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )

        trafficJob = scope.launch {
            webSocket.trafficFlow()
                .catch { Log.w(TAG, "Traffic flow error: $it") }
                .collect { traffic ->
                    if (!isScreenOn) return@collect
                    val notification = NotificationHelper.buildDynamicNotification(
                        context = this@MishkaTunService,
                        profileName = profileName,
                        uploadTotal = FormatUtils.formatBytes(traffic.upTotal),
                        downloadTotal = FormatUtils.formatBytes(traffic.downTotal),
                        uploadSpeed = FormatUtils.formatSpeed(traffic.up),
                        downloadSpeed = FormatUtils.formatSpeed(traffic.down),
                    )
                    notificationManager?.notify(NotificationHelper.NOTIFICATION_ID_VPN, notification)
                }
        }
    }

    private fun stopDynamicNotification() {
        trafficJob?.cancel()
        trafficJob = null
        screenReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        screenReceiver = null
    }

    private fun stopProxy() {
        Log.i(TAG, "Stopping proxy...")
        stopDynamicNotification()
        runner.stop()
        closeTunFd()
        ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopped))
        PlatformStorage(this).putString("service_was_running", "false")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeTunFd() {
        if (tunFd >= 0) {
            try {
                val fileDescriptor = java.io.FileDescriptor()
                val field = java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
                field.isAccessible = true
                field.setInt(fileDescriptor, tunFd)
                android.system.Os.close(fileDescriptor)
                Log.i(TAG, "Closed tun fd=$tunFd")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close tun fd=$tunFd: $e")
            }
            tunFd = -1
        }
    }

    override fun onDestroy() {
        stopDynamicNotification()
        runner.stop()
        closeTunFd()
        ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopped))
        scope.cancel()
        Log.i(TAG, "MishkaTunService destroyed")
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked by system")
        stopProxy()
    }

    companion object {
        private const val TAG = "MishkaTunService"
        const val ACTION_START = "top.yukonga.mishka.START"
        const val ACTION_STOP = "top.yukonga.mishka.STOP"
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
