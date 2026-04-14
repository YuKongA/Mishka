package top.yukonga.mishka.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyServiceStatus
import top.yukonga.mishka.platform.ProxyState

class MishkaTunService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runner by lazy { MihomoRunner(this) }
    private var tunFd: Int = -1

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
            val fd = try {
                Builder().apply {
                    addAddress(TUN_GATEWAY, TUN_SUBNET_PREFIX)
                    addRoute("0.0.0.0", 0)
                    addDnsServer(TUN_DNS)
                    setMtu(TUN_MTU)
                    setSession("Mishka")
                    setBlocking(false)

                    // 应用代理设置
                    val storage = PlatformStorage(this@MishkaTunService)
                    val proxyMode = storage.getString("app_proxy_mode", "AllowAll")
                    val packagesStr = storage.getString("app_proxy_packages", "")
                    val packages = if (packagesStr.isBlank()) emptySet()
                    else packagesStr.split(",").filter { it.isNotBlank() }.toSet()

                    when (proxyMode) {
                        "AllowSelected" -> {
                            // 仅允许选中应用经过代理
                            packages.forEach { pkg ->
                                try { addAllowedApplication(pkg) } catch (_: Exception) {}
                            }
                        }
                        "DenySelected" -> {
                            // 排除选中应用 + 排除自身
                            addDisallowedApplication(packageName)
                            packages.forEach { pkg ->
                                try { addDisallowedApplication(pkg) } catch (_: Exception) {}
                            }
                        }
                        else -> {
                            // AllowAll：仅排除自身
                            addDisallowedApplication(packageName)
                        }
                    }

                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        setMetered(false)
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
            val notification = NotificationHelper.buildRunningNotification(this@MishkaTunService)
            getSystemService(android.app.NotificationManager::class.java)
                ?.notify(NotificationHelper.NOTIFICATION_ID_VALUE, notification)

            ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Running, secret = runner.secret))
            // 记录运行状态，用于开机自启判断
            PlatformStorage(this@MishkaTunService).putString("service_was_running", "true")
            Log.i(TAG, "Proxy running, fd=$fd")
        }
    }

    private fun stopProxy() {
        Log.i(TAG, "Stopping proxy...")
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
        private const val TUN_DNS = "198.18.0.2"

        fun start(context: Context) {
            val intent = Intent(context, MishkaTunService::class.java).apply {
                action = ACTION_START
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
