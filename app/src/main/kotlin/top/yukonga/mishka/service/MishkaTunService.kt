package top.yukonga.mishka.service

import android.annotation.SuppressLint
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import top.yukonga.mishka.MishkaApplication
import top.yukonga.mishka.R
import top.yukonga.mishka.data.repository.OverrideJsonStore
import top.yukonga.mishka.data.repository.SubscriptionRepositoryImpl
import top.yukonga.mishka.data.store.ProfileTransformWriter
import top.yukonga.mishka.domain.model.resolveExternalController
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.platform.ProxyServiceStatus
import top.yukonga.mishka.platform.ProxyState
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.TunMode
import top.yukonga.mishka.util.describe
import java.io.File
import kotlin.time.Clock

@SuppressLint("VpnServicePolicy")
class MishkaTunService : VpnService() {

    // 启停链全程阻塞：孤儿清理的 su 调用、mihomo fork+exec 与 waitForReady 轮询、日志尾读。
    // 跑在 Default 上会长时间占住数个 CPU 池线程，与 Compose 重组、导入管线抢同一批核
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifetime = VpnRuntimeHandoff.claim()
    private val lifecycleLock = Any()
    private var destroyed = false
    private var foregroundFailure: ProxyServiceStatus? = null
    private val runner by lazy { MihomoRunner(this) }
    private val dynamicNotification by lazy {
        DynamicNotificationManager(this, scope, MishkaApplication.instance.connectionManager)
    }

    // 取 Koin 单例而非自建：store 的内存值是权威值，自建实例读不到 UI 侧刚落的设置
    private val overrideStore: OverrideJsonStore by inject()
    private val transformWriter: ProfileTransformWriter by inject()
    private val subscriptionRepository: SubscriptionRepositoryImpl by inject()
    private var tunFd: Int = -1
    private var httpListenerFd: Int = -1
    private var platformBridge: VpnPlatformBridge? = null
    private var foregroundReady = false
    private var monitorJob: Job? = null
    private var notificationRefreshJob: Job? = null

    private data class VpnCommand(
        val action: String,
        val subscriptionId: String? = null,
        val startId: Int = 0,
        val generation: Long = -1,
        val error: String? = null,
    )

    // START 只启动可取消任务；STOP/RESTART 在同一个消费方等待它收敛，不能各自并发清理 fd。
    private val commands = Channel<VpnCommand>(Channel.UNLIMITED)
    private var startJob: Job? = null
    private var generation = 0L
    private var activeStartId = 0
    private var latestStartId = 0

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
            foregroundFailure = ProxyServiceStatus(
                ProxyState.Error,
                errorMessage = getString(R.string.error_foreground_failed, e.message ?: e.javaClass.simpleName),
                tunMode = TunMode.Vpn,
            )
            stopSelf()
            return
        }
        foregroundReady = true
        scope.launch {
            lifetime.awaitPredecessor()
            for (command in commands) {
                try {
                    when (command.action) {
                        ACTION_START -> {
                            val id = command.subscriptionId
                                ?: ProxyServiceController(this@MishkaTunService).resolveStartSubscriptionId()
                            if (id != null) startProxy(id, command.startId) else stopSelfResult(command.startId)
                        }

                        ACTION_RESTART -> restartProxy(command.subscriptionId, command.startId)
                        ACTION_STOP -> stopProxy(command.startId)
                        ACTION_DIED -> if (generation == command.generation) stopProxy(activeStartId, command.error)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "VPN command failed", e)
                    ProxyServiceBridge.updateState(
                        ProxyServiceStatus(ProxyState.Error, errorMessage = e.describe(), tunMode = TunMode.Vpn)
                    )
                    if (command.startId > 0) stopSelfResult(command.startId) else stopSelf()
                }
            }
        }
        // 监听动态通知设置变化，实时切换通知样式
        notificationRefreshJob = scope.launch {
            lifetime.awaitPredecessor()
            ProxyServiceBridge.notificationRefresh.collect {
                val state = ProxyServiceBridge.state.value
                if (state.state == ProxyState.Running && state.tunMode == TunMode.Vpn) {
                    dynamicNotification.stop()
                    dynamicNotification.startOrFallbackStatic(
                        PlatformStorage(this@MishkaTunService),
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady) return START_NOT_STICKY
        latestStartId = startId
        when (intent?.action) {
            ACTION_START, ACTION_STOP, ACTION_RESTART -> commands.trySend(
                VpnCommand(
                    action = requireNotNull(intent.action),
                    subscriptionId = intent.getStringExtra(ProxyServiceController.EXTRA_SUBSCRIPTION_ID),
                    startId = startId,
                )
            )

            null, SERVICE_INTERFACE -> {
                // 校验也在前驱收敛后执行，不能从新实例的主线程覆盖旧实例状态。
                commands.trySend(VpnCommand(ACTION_START, startId = startId))
            }
        }
        return START_STICKY
    }

    /** 启动和取消均由 Service 拥有；失败时关闭父进程持有的全部 VPN 资源。 */
    private fun startProxy(subscriptionId: String?, startId: Int) {
        activeStartId = startId
        if (startJob?.isActive == true || (runner.isRunning && ProxyServiceBridge.state.value.state == ProxyState.Running)) {
            Log.i(TAG, "VPN already starting or running, ignoring duplicate START")
            return
        }
        val runGeneration = ++generation
        startJob = scope.launch {
            var started = false
            val storage = PlatformStorage(this@MishkaTunService)
            try {
                if (!ProfileFileOps.hasValidConfig(this@MishkaTunService, subscriptionId)) {
                    throw IllegalStateException(getString(R.string.error_no_active_profile))
                }
                ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Starting, tunMode = TunMode.Vpn))
                runner.stop()
                closeVpnResources()

                // ROOT 孤儿仍可能持有路由；切回 VPN 前保留既有清理和缓存回写。
                val hadRootPid = storage.getString(StorageKeys.ROOT_MIHOMO_PID, "").isNotEmpty()
                val hasRoot = storage.getString(StorageKeys.HAS_ROOT, "false") == "true"
                if (hadRootPid || hasRoot) {
                    val residualTun = storage.getString(StorageKeys.ROOT_TUN_DEVICE, RuntimeOverrideBuilder.DEFAULT_TUN_DEVICE)
                    RootHelper.cleanupOrphanedMihomo(tunDevice = residualTun)
                    RootTetherHijacker.teardown()
                    RootTproxyApplier.teardown()
                    check(RootDnsApplier.teardown()) { "Failed to remove ROOT DNS routing" }
                    RootRuntimeCache.releaseAll(this@MishkaTunService, subscriptionRepository)
                    storage.putString(StorageKeys.ROOT_MIHOMO_PID, "")
                    storage.putString(StorageKeys.ROOT_MIHOMO_SECRET, "")
                    storage.putString(StorageKeys.ROOT_ACTIVE_SUBSCRIPTION_ID, "")
                }

                val userOverride = overrideStore.load()
                val viaProxy = storage.getString(StorageKeys.SUBSCRIPTION_UPDATE_VIA_PROXY, "true") == "true"
                val transformPlan = prepareRuntimeTransform(
                    this@MishkaTunService, subscriptionId, subscriptionRepository, transformWriter,
                )
                ensureActive()
                val ipv6 = storage.getString(StorageKeys.VPN_ALLOW_IPV6, "false") == "true"
                platformBridge = VpnPlatformBridge(this@MishkaTunService, scope) { error ->
                    Log.e(TAG, "VPN platform bridge failed", error)
                    commands.trySend(
                        VpnCommand(ACTION_DIED, generation = runGeneration, error = error.describe())
                    )
                }.also { it.start() }
                val systemProxy = storage.getString(StorageKeys.VPN_SYSTEM_PROXY, "true") != "false"
                val proxyAddress = if (systemProxy) {
                    val fd = ProcessHelper.nativeCreateHttpListener()
                    if (fd < 0) {
                        // 独立 HTTP 入口不可用时不发布失效的系统代理。
                        Log.w(TAG, "System HTTP listener unavailable, using TUN only")
                        null
                    } else {
                        httpListenerFd = fd
                        val address = ProcessHelper.nativeHttpListenerAddress(fd)
                        val port = ProcessHelper.nativeHttpListenerPort(fd)
                        check(address != null && port in 1..65535) { "Invalid system HTTP listener address" }
                        address to port
                    }
                } else null

                tunFd = establishVpn(storage, ipv6, proxyAddress)
                    ?: throw IllegalStateException(getString(R.string.error_vpn_denied))
                ensureActive()
                clearCloseOnExec(tunFd)
                val secret = ConfigGenerator.resolveSecret(this@MishkaTunService, userOverride, subscriptionId)
                val extCtl = userOverride.resolveExternalController()
                val overrideFile = RuntimeOverrideBuilder.buildAndWriteForRun(
                    context = this@MishkaTunService,
                    userOverride = userOverride,
                    tunFd = tunFd,
                    tunMode = TunMode.Vpn,
                    subscriptionUpdateViaProxy = viaProxy,
                    subscriptionMixedPort = transformPlan.mixedPort,
                )
                val gateway = buildList {
                    add("${RuntimeOverrideBuilder.VPN_GATEWAY}/${RuntimeOverrideBuilder.VPN_PREFIX}")
                    if (ipv6) add("${RuntimeOverrideBuilder.VPN_GATEWAY6}/${RuntimeOverrideBuilder.VPN_PREFIX6}")
                }.joinToString(",")
                val success = runner.start(
                    subscriptionId = subscriptionId,
                    useRoot = false,
                    overrideJsonPath = overrideFile.absolutePath,
                    secret = secret,
                    externalController = extCtl,
                    ageSecretKey = transformPlan.ageSecretKey,
                    transformPath = transformPlan.transformPath,
                    preferTransformMixedPort = transformPlan.transformPath != null && viaProxy && userOverride.mixedPort == null,
                    vpnGateway = gateway,
                    vpnHttpFd = httpListenerFd,
                    vpnPlatformSocket = requireNotNull(platformBridge).socketName,
                )
                if (!success) throw IllegalStateException(runner.errorMessage.ifBlank { getString(R.string.error_start_failed) })
                ensureActive()
                val published = synchronized(lifecycleLock) {
                    if (destroyed) false else {
                        ProxyServiceBridge.updateState(
                            ProxyServiceStatus(
                                ProxyState.Running,
                                secret = runner.secret,
                                externalController = extCtl,
                                tunMode = TunMode.Vpn,
                                startTime = Clock.System.now().toEpochMilliseconds(),
                                mihomoPid = runner.pid,
                            )
                        )
                        storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "true")
                        true
                    }
                }
                if (!published) throw CancellationException("VPN Service destroyed before startup completed")
                dynamicNotification.startOrFallbackStatic(storage)
                val workDir = subscriptionId?.let { ProfileFileOps.getSubscriptionDir(this@MishkaTunService, it) }
                    ?: ConfigGenerator.getWorkDir(this@MishkaTunService)
                startProcessMonitor(workDir, runGeneration)
                started = true
                Log.i(TAG, "VPN running, fd=$tunFd")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "VPN startup failed", e)
                commands.trySend(
                    VpnCommand(
                        ACTION_DIED,
                        generation = runGeneration,
                        error = getString(R.string.error_generic_start_failed, e.describe()),
                    )
                )
            } finally {
                if (!started) {
                    dynamicNotification.stop()
                    try {
                        runner.stop()
                        closeVpnResources()
                        storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
                    } catch (e: Exception) {
                        Log.e(TAG, "VPN startup cleanup is still pending", e)
                        commands.trySend(VpnCommand(ACTION_DIED, generation = runGeneration, error = e.describe()))
                    }
                }
            }
        }
    }

    private fun establishVpn(
        storage: PlatformStorage,
        ipv6: Boolean,
        proxyAddress: Pair<String, Int>?,
    ): Int? = Builder().apply {
        addAddress(RuntimeOverrideBuilder.VPN_GATEWAY, RuntimeOverrideBuilder.VPN_PREFIX)
        setMtu(RuntimeOverrideBuilder.VPN_TUN_MTU)
        setSession("Mishka")
        setBlocking(false)
        val bypassPrivate = storage.getString(StorageKeys.VPN_BYPASS_PRIVATE_NETWORK, "true") == "true"
        if (bypassPrivate) {
            resources.getStringArray(R.array.bypass_private_route).forEach { cidr ->
                val (address, prefix) = cidr.split("/")
                addRoute(address, prefix.toInt())
            }
            addRoute(RuntimeOverrideBuilder.VPN_DNS, 32)
        } else {
            addRoute("0.0.0.0", 0)
        }
        if (ipv6) {
            addAddress(RuntimeOverrideBuilder.VPN_GATEWAY6, RuntimeOverrideBuilder.VPN_PREFIX6)
            if (bypassPrivate) {
                resources.getStringArray(R.array.bypass_private_route6).forEach { cidr ->
                    val (address, prefix) = cidr.split("/")
                    addRoute(address, prefix.toInt())
                }
                addRoute(RuntimeOverrideBuilder.VPN_DNS6, 128)
            } else {
                addRoute("::", 0)
            }
        }
        // 开关控制的是额外 DNS 的劫持范围，虚拟 DNS 本身始终对应用可用。
        addDnsServer(RuntimeOverrideBuilder.VPN_DNS)
        if (ipv6) addDnsServer(RuntimeOverrideBuilder.VPN_DNS6)
        if (storage.getString(StorageKeys.VPN_ALLOW_BYPASS, "true") == "true") allowBypass()

        val packages = storage.getStringSet(StorageKeys.APP_PROXY_PACKAGES, emptySet())
        when (storage.getString(StorageKeys.APP_PROXY_MODE, "AllowAll")) {
            "AllowSelected" -> {
                // self 必须绕过：空/已卸载名单不能退化为默认全允许。
                val installed = packages.filter { pkg ->
                    try {
                        packageManager.getApplicationInfo(pkg, 0).uid != android.os.Process.myUid()
                    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                        false
                    }
                }
                check(installed.isNotEmpty()) { getString(R.string.error_vpn_no_selected_apps) }
                installed.forEach { addAllowedApplication(it) }
            }

            "DenySelected" -> {
                addDisallowedApplication(packageName)
                packages.filter { it != packageName }.forEach { pkg ->
                    try {
                        addDisallowedApplication(pkg)
                    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                        // 用户可能在配置名单后卸载应用。
                    }
                }
            }

            else -> addDisallowedApplication(packageName)
        }
        setMetered(false)
        proxyAddress?.let { (address, port) ->
            val exclusions = HTTP_PROXY_BLACK_LIST + if (bypassPrivate) HTTP_PROXY_LOCAL_LIST else emptyList()
            setHttpProxy(android.net.ProxyInfo.buildDirectProxy(address, port, exclusions))
        }
    }.establish()?.detachFd()

    private fun clearCloseOnExec(fd: Int) {
        val descriptor = ParcelFileDescriptor.adoptFd(fd)
        try {
            val flags = Os.fcntlInt(descriptor.fileDescriptor, OsConstants.F_GETFD, 0)
            Os.fcntlInt(descriptor.fileDescriptor, OsConstants.F_SETFD, flags and OsConstants.FD_CLOEXEC.inv())
        } finally {
            descriptor.detachFd()
        }
    }

    @SuppressLint("StringFormatInvalid")
    private fun startProcessMonitor(workDir: File, runGeneration: Long) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            // 等待一段时间再开始监测，避免与 waitForReady 重叠
            delay(10_000)
            while (runner.isRunning) {
                delay(5_000)
            }
            // 进程异常退出
            val logContent = File(workDir, "mihomo.log").readLastLines(DEATH_LOG_LINES)
            val errorMsg = if (logContent.isNotBlank()) {
                getString(R.string.error_mihomo_start_failed, logContent)
            } else {
                getString(R.string.error_mihomo_exited)
            }
            commands.trySend(VpnCommand(ACTION_DIED, generation = runGeneration, error = errorMsg))
        }
    }

    private suspend fun restartProxy(subscriptionId: String?, startId: Int) {
        Log.i(TAG, "Restarting proxy...")
        generation++
        monitorJob?.cancelAndJoin()
        ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopping, tunMode = TunMode.Vpn))
        dynamicNotification.stop()
        startJob?.cancelAndJoin()
        runner.stop()
        closeVpnResources()
        startProxy(subscriptionId, startId)
    }

    private suspend fun stopProxy(startId: Int = 0, error: String? = null) {
        generation++
        monitorJob?.cancelAndJoin()
        ProxyServiceBridge.updateState(
            ProxyServiceStatus(
                state = if (error == null) ProxyState.Stopping else ProxyState.Error,
                errorMessage = error.orEmpty(),
                tunMode = TunMode.Vpn,
            )
        )
        dynamicNotification.stop()
        startJob?.cancelAndJoin()
        runner.stop()
        closeVpnResources()
        PlatformStorage(this).putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
        ProxyServiceBridge.markStoppedUnlessError(TunMode.Vpn)
        // 新的 START 已投递时不能终止承载它的 Service。
        if (startId > 0) stopSelfResult(startId) else stopSelf()
    }

    private fun closeVpnResources() {
        platformBridge?.close()
        platformBridge = null
        closeOwnedFd(tunFd)
        tunFd = -1
        closeOwnedFd(httpListenerFd)
        httpListenerFd = -1
    }

    private fun closeOwnedFd(fd: Int) {
        if (fd < 0) return
        try {
            ParcelFileDescriptor.adoptFd(fd).close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close VPN fd=$fd", e)
        }
    }

    override fun onDestroy() {
        synchronized(lifecycleLock) { destroyed = true }
        foregroundReady = false
        val serviceJob = scope.coroutineContext[Job]
        commands.close()
        scope.cancel()
        dynamicNotification.stop()
        // 后继实例必须等待这一代完成收尾，不能在旧核心仍占端口/持 fd 时 fork 新核心。
        val teardown = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        teardown.launch {
            serviceJob?.join()
            lifetime.awaitPredecessor()
            while (true) {
                try {
                    runner.stop()
                    closeVpnResources()
                    break
                } catch (e: Exception) {
                    // SIGKILL 后尚未回收也仍持有子进程；不能谎报停止或放行下一代。
                    Log.e(TAG, "VPN cleanup is still pending", e)
                    ProxyServiceBridge.updateState(
                        ProxyServiceStatus(ProxyState.Error, errorMessage = e.describe(), tunMode = TunMode.Vpn)
                    )
                    delay(250)
                }
            }
            PlatformStorage(this@MishkaTunService).putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
            foregroundFailure?.let(ProxyServiceBridge::updateState)
                ?: ProxyServiceBridge.markStoppedUnlessError(TunMode.Vpn)
            lifetime.finish()
            teardown.cancel()
            Log.i(TAG, "VPN Service resources released")
        }
        Log.i(TAG, "MishkaTunService destroyed")
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked by system")
        commands.trySend(VpnCommand(ACTION_STOP, startId = latestStartId))
    }

    companion object {
        private const val TAG = "MishkaTunService"

        /** 进程意外退出时随错误一起展示的日志行数 */
        private const val DEATH_LOG_LINES = 10
        const val ACTION_START = "top.yukonga.mishka.START"
        const val ACTION_STOP = "top.yukonga.mishka.STOP"
        const val ACTION_RESTART = "top.yukonga.mishka.RESTART"
        private const val ACTION_DIED = "process-died"

        private val HTTP_PROXY_LOCAL_LIST = listOf(
            "localhost", "*.local", "127.*", "10.*", "172.16.*",
            "172.17.*", "172.18.*", "172.19.*", "172.20.*",
            "172.21.*", "172.22.*", "172.23.*", "172.24.*",
            "172.25.*", "172.26.*", "172.27.*", "172.28.*",
            "172.29.*", "172.30.*", "172.31.*", "192.168.*",
        )

        private val HTTP_PROXY_BLACK_LIST = listOf(
            "*zhihu.com", "*zhimg.com", "*jd.com", "*360buyimg.com",
            "100ime-iat-api.xfyun.cn",
        )
    }
}
