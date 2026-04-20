package top.yukonga.mishka.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.mishka.R
import top.yukonga.mishka.data.model.resolveExternalController
import top.yukonga.mishka.data.model.resolveSecretOrNull
import top.yukonga.mishka.data.repository.OverrideJsonStore
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyServiceStatus
import top.yukonga.mishka.platform.ProxyState
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.TunMode
import java.io.File
import kotlin.time.Clock

/**
 * ROOT TUN 模式前台服务。
 * 不经过 VpnService，mihomo 以 root 权限自行创建 TUN 设备和管理路由表。
 */
class MishkaRootService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runner by lazy { MihomoRunner(this) }
    private val dynamicNotification by lazy { DynamicNotificationManager(this, scope) }
    private val overrideStore by lazy { OverrideJsonStore(AndroidProfileFileManager(this)) }
    private var monitorJob: Job? = null
    private var notificationRefreshJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

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
                    tunMode = TunMode.Root,
                )
            )
            stopSelf()
            return
        }
        // 监听动态通知设置变化，实时切换通知样式
        notificationRefreshJob = scope.launch {
            ProxyServiceBridge.notificationRefresh.collect {
                val state = ProxyServiceBridge.state.value
                if (state.state == ProxyState.Running && state.tunMode == TunMode.Root) {
                    dynamicNotification.stop()
                    dynamicNotification.startOrFallbackStatic(
                        PlatformStorage(this@MishkaRootService),
                        state.secret,
                        state.externalController,
                        TunMode.Root,
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

    private fun startProxy(subscriptionId: String? = null) {
        scope.launch {
            Log.i(TAG, "Starting proxy (ROOT), subscription: $subscriptionId")
            ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Starting, tunMode = TunMode.Root))

            val storage = PlatformStorage(this@MishkaRootService)
            val tetherModeRequested = storage.getString(
                StorageKeys.ROOT_TETHER_MODE,
                RootTetherHijacker.Mode.BYPASS.storageValue,
            )
            val tetherModeActive = storage.getString(StorageKeys.ROOT_TETHER_MODE_ACTIVE, "")

            // 1. 尝试重连已有的 mihomo 进程（app 被杀后 root 进程仍存活）
            val existingPid = storage.getString(StorageKeys.ROOT_MIHOMO_PID, "").toIntOrNull() ?: -1
            val existingSecret = storage.getString(StorageKeys.ROOT_MIHOMO_SECRET, "")
            val existingSubscriptionId = storage.getString(StorageKeys.ROOT_ACTIVE_SUBSCRIPTION_ID, "").ifEmpty { null }
            // 请求的订阅与运行中的不一致时拒绝重连，走全新启动加载新 config
            val subscriptionMismatch = existingPid > 0 && subscriptionId != existingSubscriptionId
            // 用户在 app 被杀期间改过 tether mode：运行中的 mihomo 的 tproxy-port 配置已锁死，
            // 再 attach 上去 hijacker 规则会与 mihomo 实际监听状态错位（PROXY 的 TPROXY 规则
            // 指向不存在的端口 / BYPASS 下无效的 tproxy 进程）→ 拒绝 attach，走全新启动
            val tetherModeMismatch = existingPid > 0 &&
                tetherModeActive.isNotEmpty() &&
                tetherModeActive != tetherModeRequested
            if (subscriptionMismatch) {
                Log.i(TAG, "Existing process pid=$existingPid runs subscription=$existingSubscriptionId, requested=$subscriptionId, restarting")
            }
            if (tetherModeMismatch) {
                Log.i(TAG, "Tether mode changed while app was killed (active=$tetherModeActive, requested=$tetherModeRequested), restarting")
            }
            if (existingPid > 0 && existingSecret.isNotEmpty() && !subscriptionMismatch && !tetherModeMismatch) {
                val ec = overrideStore.load().resolveExternalController()
                if (runner.attachToExisting(existingPid, existingSecret, ec, subscriptionId)) {
                    val existingStartTime = storage.getString(StorageKeys.ROOT_START_TIME, "").toLongOrNull() ?: Clock.System.now().toEpochMilliseconds()
                    Log.i(TAG, "Reconnected to existing mihomo: pid=$existingPid")
                    // App 进程重启后内存态丢失：重新 probe TPROXY（内核能力），再 apply
                    // 幂等规则；这里读 active mode 因为它对应 mihomo 实际启动时的配置
                    val activeMode = RootTetherHijacker.Mode.from(
                        tetherModeActive.ifEmpty { tetherModeRequested }
                    )
                    val tproxySupported = activeMode == RootTetherHijacker.Mode.PROXY &&
                        RootTetherHijacker.probeTproxySupport()
                    applyTetherRulesActive(storage, tproxySupported)
                    ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Running, secret = existingSecret, externalController = ec, tunMode = TunMode.Root, startTime = existingStartTime, mihomoPid = runner.pid))
                    dynamicNotification.startOrFallbackStatic(storage, existingSecret, ec, TunMode.Root)
                    storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "true")
                    // 重连到活着的 mihomo：它仍在 runtime/{uuid}/ 下跑，监控日志从同一目录读
                    val workDir = if (subscriptionId != null) ProfileFileOps.getRuntimeDir(this@MishkaRootService, subscriptionId) else ConfigGenerator.getWorkDir(this@MishkaRootService)
                    startProcessMonitor(workDir)
                    return@launch
                }
                Log.i(TAG, "Existing process pid=$existingPid failed attach verification, cleaning up")
                clearPersistedState(storage)
            }

            // 2. 清理残留进程（上次的进程已失效，确保干净启动）
            // 先停自身 runner（Service 实例被复用时可能仍持有旧状态），再 pkill 孤儿进程
            // 同时清理 TUN 接口防止下次启动 sing-tun EEXIST（silent failure 源头）
            if (runner.isRunning) {
                runner.stop()
            }
            val currentTun = storage.getString(StorageKeys.ROOT_TUN_DEVICE, RuntimeOverrideBuilder.DEFAULT_TUN_DEVICE)
            RootHelper.cleanupOrphanedMihomo(tunDevice = currentTun)

            // 3. 检查 ROOT 权限
            if (!RootHelper.hasRootAccess()) {
                Log.e(TAG, "Failed to obtain root access")
                ProxyServiceBridge.updateState(
                    ProxyServiceStatus(ProxyState.Error, errorMessage = getString(R.string.error_root_failed), tunMode = TunMode.Root)
                )
                stopSelf()
                return@launch
            }

            // 3.5 准备 runtime/{uuid}/ 沙箱：从 imported/{uuid}/ 复制，mihomo 在此以 root 写入
            //     不污染 imported/，保证更新/删除始终在 app UID 下工作
            if (subscriptionId != null) {
                try {
                    ProfileFileOps.prepareRootRuntime(this@MishkaRootService, subscriptionId)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to prepare runtime sandbox", e)
                    ProxyServiceBridge.updateState(
                        ProxyServiceStatus(
                            ProxyState.Error,
                            errorMessage = getString(R.string.error_generic_start_failed, e.message ?: e.javaClass.simpleName),
                            tunMode = TunMode.Root,
                        )
                    )
                    stopSelf()
                    return@launch
                }
            }

            // 4. 装配 override.run.json（ROOT 模式：auto-route / auto-detect-interface / AppProxy）
            // secret / extCtl 走 CLI flag 不进 JSON
            // secret 解析优先级：用户 override > 订阅 config.yaml 中的 secret > 随机生成
            val userOverride = overrideStore.load()
            val secret = userOverride.resolveSecretOrNull()
                ?: subscriptionId?.let { ConfigGenerator.readSubscriptionSecret(this@MishkaRootService, it) }
                ?: ConfigGenerator.generateSecret()
            val extCtl = userOverride.resolveExternalController()
            // 仅 PROXY 模式才探测 xt_TPROXY；返回 true 时给 mihomo 加 tproxy-port 入站
            // 监听，让 RootTetherHijacker 后面安装的 TPROXY iptables 规则有落点
            val tproxyForTether = tetherModeRequested == RootTetherHijacker.Mode.PROXY.storageValue &&
                RootTetherHijacker.probeTproxySupport().also { supported ->
                    if (!supported) Log.w(TAG, "xt_TPROXY unavailable, PROXY degraded to userspace TUN")
                }
            val overrideFile = RuntimeOverrideBuilder.buildAndWriteForRun(
                context = this@MishkaRootService,
                userOverride = userOverride,
                tunFd = -1,
                rootMode = true,
                tproxyForTether = tproxyForTether,
            )

            // 5. 以 root 启动 mihomo
            val success = runner.start(
                subscriptionId = subscriptionId,
                useRoot = true,
                overrideJsonPath = overrideFile.absolutePath,
                secret = secret,
                externalController = extCtl,
            )
            if (!success) {
                val errorMsg = runner.errorMessage.ifBlank { getString(R.string.error_start_failed) }
                Log.e(TAG, "Failed to start mihomo (ROOT): $errorMsg")
                ProxyServiceBridge.updateState(
                    ProxyServiceStatus(ProxyState.Error, errorMessage = errorMsg, tunMode = TunMode.Root)
                )
                stopSelf()
                return@launch
            }

            // 6. 持久化 PID、secret、启动时间和订阅 ID（用于 app 重启后重连 + 订阅一致性校验）
            val startTime = Clock.System.now().toEpochMilliseconds()
            persistState(storage, runner.secret, startTime, subscriptionId)
            // 保存本次启动时生效的 tether mode 快照，attach 路径据此判断用户是否改过模式
            storage.putString(StorageKeys.ROOT_TETHER_MODE_ACTIVE, tetherModeRequested)

            // 6.5 apply 热点流量处置规则（绕过代理 / 走代理）
            applyTetherRules(storage, tproxyForTether)

            // 7. 更新状态和通知
            ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Running, secret = runner.secret, externalController = extCtl, tunMode = TunMode.Root, startTime = startTime, mihomoPid = runner.pid))
            dynamicNotification.startOrFallbackStatic(storage, runner.secret, extCtl, TunMode.Root)
            storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "true")
            Log.i(TAG, "Proxy running (ROOT)")

            val workDir = if (subscriptionId != null) ProfileFileOps.getRuntimeDir(this@MishkaRootService, subscriptionId) else ConfigGenerator.getWorkDir(this@MishkaRootService)
            startProcessMonitor(workDir)
        }
    }

    private fun startProcessMonitor(workDir: File) {
        monitorJob?.cancel()
        monitorJob = scope.launch(Dispatchers.IO) {
            delay(10_000)
            while (runner.isRunning) {
                delay(5_000)
            }
            // ROOT 进程异常退出
            val logContent = RootHelper.readLogFile(File(workDir, "mihomo.log").absolutePath)
            val errorMsg = if (logContent.isNotBlank()) {
                getString(R.string.error_mihomo_start_failed, logContent)
            } else {
                getString(R.string.error_mihomo_exited)
            }
            Log.e(TAG, "mihomo process died unexpectedly (ROOT): $errorMsg")
            val storage = PlatformStorage(this@MishkaRootService)
            val runningSubscriptionId = storage.getString(StorageKeys.ROOT_ACTIVE_SUBSCRIPTION_ID, "").ifEmpty { null }
            withContext(NonCancellable) { RootTetherHijacker.teardown() }
            clearPersistedState(storage)
            storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
            // 进程死透了，清 runtime/{uuid}/（里面有 root:root 的 provider 缓存，app 删不动）
            runningSubscriptionId?.let { ProfileFileOps.cleanupRootRuntime(this@MishkaRootService, it) }
            ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Error, errorMessage = errorMsg, tunMode = TunMode.Root))
            dynamicNotification.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun persistState(storage: PlatformStorage, secret: String, startTime: Long, subscriptionId: String?) {
        storage.putString(StorageKeys.ROOT_MIHOMO_PID, runner.pid.toString())
        storage.putString(StorageKeys.ROOT_MIHOMO_SECRET, secret)
        storage.putString(StorageKeys.ROOT_START_TIME, startTime.toString())
        storage.putString(StorageKeys.ROOT_ACTIVE_SUBSCRIPTION_ID, subscriptionId ?: "")
    }

    private fun clearPersistedState(storage: PlatformStorage) {
        storage.putString(StorageKeys.ROOT_MIHOMO_PID, "")
        storage.putString(StorageKeys.ROOT_MIHOMO_SECRET, "")
        storage.putString(StorageKeys.ROOT_START_TIME, "")
        storage.putString(StorageKeys.ROOT_ACTIVE_SUBSCRIPTION_ID, "")
        storage.putString(StorageKeys.ROOT_TETHER_MODE_ACTIVE, "")
    }

    /** 全新启动路径：用当前设置的 tether mode + 外部传入的 TPROXY 能力 apply 规则。 */
    private fun applyTetherRules(storage: PlatformStorage, tproxySupported: Boolean) {
        val mode = RootTetherHijacker.Mode.from(
            storage.getString(StorageKeys.ROOT_TETHER_MODE, RootTetherHijacker.Mode.BYPASS.storageValue)
        )
        val ifaces = RootTetherHijacker.parseInterfaces(
            storage.getString(StorageKeys.ROOT_TETHER_IFACES, RootTetherHijacker.DEFAULT_IFACES)
        )
        RootTetherHijacker.apply(mode, ifaces, tproxySupported)
    }

    /** Attach 路径：用上次启动时持久化的 active mode（与 mihomo 实际监听状态对齐）。 */
    private fun applyTetherRulesActive(storage: PlatformStorage, tproxySupported: Boolean) {
        val activeStr = storage.getString(StorageKeys.ROOT_TETHER_MODE_ACTIVE, "")
            .ifEmpty { storage.getString(StorageKeys.ROOT_TETHER_MODE, RootTetherHijacker.Mode.BYPASS.storageValue) }
        val mode = RootTetherHijacker.Mode.from(activeStr)
        val ifaces = RootTetherHijacker.parseInterfaces(
            storage.getString(StorageKeys.ROOT_TETHER_IFACES, RootTetherHijacker.DEFAULT_IFACES)
        )
        RootTetherHijacker.apply(mode, ifaces, tproxySupported)
    }

    private fun restartProxy(subscriptionId: String?) {
        Log.i(TAG, "Restarting proxy (ROOT)...")
        monitorJob?.cancel()
        ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopping, tunMode = TunMode.Root))
        dynamicNotification.stop()
        scope.launch(Dispatchers.IO) {
            val storage = PlatformStorage(this@MishkaRootService)
            val runningSubscriptionId = storage.getString(StorageKeys.ROOT_ACTIVE_SUBSCRIPTION_ID, "").ifEmpty { null }
            runner.stop()
            withContext(NonCancellable) { RootTetherHijacker.teardown() }
            // 清掉上一轮 runtime 沙箱，下轮 startProxy 会 prepareRootRuntime 重新从 imported/ 复制
            runningSubscriptionId?.let { ProfileFileOps.cleanupRootRuntime(this@MishkaRootService, it) }
            clearPersistedState(storage)
            withContext(Dispatchers.Main) {
                startProxy(subscriptionId)
            }
        }
    }

    private fun stopProxy() {
        Log.i(TAG, "Stopping proxy (ROOT)...")
        monitorJob?.cancel()
        ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopping, tunMode = TunMode.Root))
        dynamicNotification.stop()
        scope.launch(Dispatchers.IO) {
            val storage = PlatformStorage(this@MishkaRootService)
            val runningSubscriptionId = storage.getString(StorageKeys.ROOT_ACTIVE_SUBSCRIPTION_ID, "").ifEmpty { null }
            runner.stop()
            withContext(NonCancellable) { RootTetherHijacker.teardown() }
            runningSubscriptionId?.let { ProfileFileOps.cleanupRootRuntime(this@MishkaRootService, it) }
            clearPersistedState(storage)
            storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
            ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopped))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        notificationRefreshJob?.cancel()
        monitorJob?.cancel()
        dynamicNotification.stop()
        // 注意：onDestroy 不 kill mihomo，让它继续运行以便重连
        ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopped))
        scope.cancel()
        Log.i(TAG, "MishkaRootService destroyed")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MishkaRootService"
        const val ACTION_START = "top.yukonga.mishka.ROOT_START"
        const val ACTION_STOP = "top.yukonga.mishka.ROOT_STOP"
        const val ACTION_RESTART = "top.yukonga.mishka.ROOT_RESTART"
        const val EXTRA_SUBSCRIPTION_ID = "subscription_id"

        fun start(context: Context, subscriptionId: String? = null) {
            val intent = Intent(context, MishkaRootService::class.java).apply {
                action = ACTION_START
                subscriptionId?.let { putExtra(EXTRA_SUBSCRIPTION_ID, it) }
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MishkaRootService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
