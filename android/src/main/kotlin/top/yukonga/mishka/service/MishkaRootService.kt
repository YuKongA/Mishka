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
import top.yukonga.mishka.platform.AppListProvider
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyServiceStatus
import top.yukonga.mishka.platform.ProxyState
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.TunMode
import top.yukonga.mishka.viewmodel.AppProxyMode
import java.io.File
import kotlin.time.Clock

/**
 * ROOT 模式前台服务（承载 ROOT TUN 与 ROOT TPROXY 两个 submode）。
 * 不经过 VpnService，mihomo 以 root 权限运行：
 * - RootTun：mihomo 自行创建 TUN 设备 + sing-tun auto-route 管路由表；
 * - RootTproxy：mihomo 关闭 TUN，走 tproxy-port 监听，iptables/ip rule 透明劫持流量。
 */
class MishkaRootService : Service() {

    /**
     * ROOT 模式的子形态。Intent EXTRA_SUBMODE 取 "tun"/"tproxy"；
     * 缺省（为空或未知）兼容旧版迁移为 Tun。
     */
    private enum class Submode(val storageValue: String, val tunMode: TunMode) {
        Tun("tun", TunMode.RootTun),
        Tproxy("tproxy", TunMode.RootTproxy);

        companion object {
            fun from(value: String?): Submode = entries.firstOrNull { it.storageValue == value } ?: Tun
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runner by lazy { MihomoRunner(this) }
    private val dynamicNotification by lazy { DynamicNotificationManager(this, scope) }
    private val overrideStore by lazy { OverrideJsonStore(AndroidProfileFileManager(this)) }
    private var monitorJob: Job? = null
    private var notificationRefreshJob: Job? = null
    // 当前正在运行的 submode；由 onStartCommand 的 EXTRA_SUBMODE 设定
    @Volatile
    private var currentSubmode: Submode = Submode.Tun

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
                    tunMode = currentSubmode.tunMode,
                )
            )
            stopSelf()
            return
        }
        // 监听动态通知设置变化，实时切换通知样式
        notificationRefreshJob = scope.launch {
            ProxyServiceBridge.notificationRefresh.collect {
                val state = ProxyServiceBridge.state.value
                if (state.state == ProxyState.Running && isRootRunning(state.tunMode)) {
                    dynamicNotification.stop()
                    dynamicNotification.startOrFallbackStatic(
                        PlatformStorage(this@MishkaRootService),
                        state.secret,
                        state.externalController,
                        state.tunMode,
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 优先用 Intent 带的 submode；缺失时读 storage active snapshot 作兜底（stop/restart 路径）
        val requested = Submode.from(intent?.getStringExtra(EXTRA_SUBMODE))
        currentSubmode = if (intent?.hasExtra(EXTRA_SUBMODE) == true) {
            requested
        } else {
            val active = PlatformStorage(this).getString(StorageKeys.ROOT_SUBMODE_ACTIVE, "")
            Submode.from(active.ifEmpty { requested.storageValue })
        }
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

    private fun isRootRunning(mode: TunMode): Boolean =
        mode == TunMode.RootTun || mode == TunMode.RootTproxy

    private fun startProxy(subscriptionId: String? = null) {
        scope.launch {
            val submode = currentSubmode
            val tunMode = submode.tunMode
            Log.i(TAG, "Starting proxy (ROOT $submode), subscription: $subscriptionId")
            ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Starting, tunMode = tunMode))

            val storage = PlatformStorage(this@MishkaRootService)
            val tetherModeRequested = storage.getString(
                StorageKeys.ROOT_TETHER_MODE,
                RootTetherHijacker.Mode.BYPASS.storageValue,
            )
            val tetherModeActive = storage.getString(StorageKeys.ROOT_TETHER_MODE_ACTIVE, "")
            val submodeActive = storage.getString(StorageKeys.ROOT_SUBMODE_ACTIVE, "")

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
            // 用户在 app 被杀期间改过 submode（TUN ↔ TPROXY）：mihomo 启动参数和 iptables
            // 规则集差异巨大，无法 attach，必须 fresh restart
            val submodeMismatch = existingPid > 0 &&
                submodeActive.isNotEmpty() &&
                submodeActive != submode.storageValue
            if (subscriptionMismatch) {
                Log.i(TAG, "Existing process pid=$existingPid runs subscription=$existingSubscriptionId, requested=$subscriptionId, restarting")
            }
            if (tetherModeMismatch) {
                Log.i(TAG, "Tether mode changed while app was killed (active=$tetherModeActive, requested=$tetherModeRequested), restarting")
            }
            if (submodeMismatch) {
                Log.i(TAG, "Submode changed while app was killed (active=$submodeActive, requested=${submode.storageValue}), restarting")
            }
            if (existingPid > 0 && existingSecret.isNotEmpty() && !subscriptionMismatch && !tetherModeMismatch && !submodeMismatch) {
                val ec = overrideStore.load().resolveExternalController()
                if (runner.attachToExisting(existingPid, existingSecret, ec, subscriptionId)) {
                    val existingStartTime = storage.getString(StorageKeys.ROOT_START_TIME, "").toLongOrNull() ?: Clock.System.now().toEpochMilliseconds()
                    Log.i(TAG, "Reconnected to existing mihomo: pid=$existingPid submode=${submode.storageValue}")
                    // App 进程重启后内存态丢失：重新 probe + apply 幂等规则
                    when (submode) {
                        Submode.Tun -> {
                            val activeMode = RootTetherHijacker.Mode.from(tetherModeActive.ifEmpty { tetherModeRequested })
                            val tproxySupported = activeMode == RootTetherHijacker.Mode.PROXY &&
                                RootTetherHijacker.probeTproxySupport()
                            applyTetherRulesActive(storage, tproxySupported)
                        }
                        Submode.Tproxy -> applyTproxyRules(storage)
                    }
                    ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Running, secret = existingSecret, externalController = ec, tunMode = tunMode, startTime = existingStartTime, mihomoPid = runner.pid))
                    dynamicNotification.startOrFallbackStatic(storage, existingSecret, ec, tunMode)
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
            // 启动前把上一次残留的 iptables 规则（另一个 submode 可能还没清干净）彻底擦一遍
            teardownAllRootRules()

            // 3. 检查 ROOT 权限
            if (!RootHelper.hasRootAccess()) {
                Log.e(TAG, "Failed to obtain root access")
                ProxyServiceBridge.updateState(
                    ProxyServiceStatus(ProxyState.Error, errorMessage = getString(R.string.error_root_failed), tunMode = tunMode)
                )
                stopSelf()
                return@launch
            }

            // 3.3 TPROXY submode 需要内核支持 xt_TPROXY；不具备时直接失败，不做静默降级
            if (submode == Submode.Tproxy && !RootTproxyApplier.probeTproxySupport()) {
                Log.e(TAG, "xt_TPROXY unsupported, cannot start ROOT TPROXY mode")
                ProxyServiceBridge.updateState(
                    ProxyServiceStatus(ProxyState.Error, errorMessage = getString(R.string.error_tproxy_unsupported), tunMode = tunMode)
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
                            tunMode = tunMode,
                        )
                    )
                    stopSelf()
                    return@launch
                }
            }

            // 4. 装配 override.run.json
            // secret / extCtl 走 CLI flag 不进 JSON
            // secret 解析优先级：用户 override > 订阅 config.yaml 中的 secret > 随机生成
            val userOverride = overrideStore.load()
            val secret = userOverride.resolveSecretOrNull()
                ?: subscriptionId?.let { ConfigGenerator.readSubscriptionSecret(this@MishkaRootService, it) }
                ?: ConfigGenerator.generateSecret()
            val extCtl = userOverride.resolveExternalController()
            // 仅 TUN submode 且用户选 PROXY 时才探测 xt_TPROXY 为 RootTetherHijacker 开 tproxy-port 入站
            val tproxyForTether = submode == Submode.Tun &&
                tetherModeRequested == RootTetherHijacker.Mode.PROXY.storageValue &&
                RootTetherHijacker.probeTproxySupport().also { supported ->
                    if (!supported) Log.w(TAG, "xt_TPROXY unavailable, PROXY degraded to userspace TUN")
                }
            val overrideFile = RuntimeOverrideBuilder.buildAndWriteForRun(
                context = this@MishkaRootService,
                userOverride = userOverride,
                tunFd = -1,
                tunMode = submode.tunMode,
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
                    ProxyServiceStatus(ProxyState.Error, errorMessage = errorMsg, tunMode = tunMode)
                )
                stopSelf()
                return@launch
            }

            // 6. 持久化 PID、secret、启动时间、订阅 ID（用于 app 重启后重连 + 订阅一致性校验）
            val startTime = Clock.System.now().toEpochMilliseconds()
            persistState(storage, runner.secret, startTime, subscriptionId)
            // 保存本次启动时生效的 tether mode / submode 快照；attach 路径据此判断用户是否改过设置
            storage.putString(StorageKeys.ROOT_TETHER_MODE_ACTIVE, tetherModeRequested)
            storage.putString(StorageKeys.ROOT_SUBMODE_ACTIVE, submode.storageValue)

            // 6.5 按 submode apply 对应的 netfilter 规则集
            when (submode) {
                Submode.Tun -> applyTetherRules(storage, tproxyForTether)
                Submode.Tproxy -> applyTproxyRules(storage)
            }

            // 7. 更新状态和通知
            ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Running, secret = runner.secret, externalController = extCtl, tunMode = tunMode, startTime = startTime, mihomoPid = runner.pid))
            dynamicNotification.startOrFallbackStatic(storage, runner.secret, extCtl, tunMode)
            storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "true")
            Log.i(TAG, "Proxy running (ROOT $submode)")

            val workDir = if (subscriptionId != null) ProfileFileOps.getRuntimeDir(this@MishkaRootService, subscriptionId) else ConfigGenerator.getWorkDir(this@MishkaRootService)
            startProcessMonitor(workDir)
        }
    }

    /**
     * ROOT TPROXY 规则装配：读 AppProxy 配置 + tether ifaces，把包名解析为 UID 后交给 Applier。
     */
    private suspend fun applyTproxyRules(storage: PlatformStorage) {
        val appUid = applicationInfo.uid
        val ifaces = RootTetherHijacker.parseInterfaces(
            storage.getString(StorageKeys.ROOT_TETHER_IFACES, RootTetherHijacker.DEFAULT_IFACES)
        )
        val proxyModeStr = storage.getString(StorageKeys.APP_PROXY_MODE, AppProxyMode.AllowAll.name)
        val proxyMode = runCatching { AppProxyMode.valueOf(proxyModeStr) }.getOrDefault(AppProxyMode.AllowAll)
        val packages = storage.getStringSet(StorageKeys.APP_PROXY_PACKAGES, emptySet())
        val selectedUids = if (packages.isEmpty()) {
            emptySet()
        } else {
            AppListProvider(this@MishkaRootService).resolveUids(packages)
        }
        RootTproxyApplier.apply(appUid, selectedUids, proxyMode, ifaces)
    }

    /**
     * 清理所有 ROOT 模式的 netfilter 规则（两个 applier 都跑，从任意前置状态清干净，
     * 不依赖内存态 currentSubmode 的准确性）。走 NonCancellable 保证 stop/restart/死亡
     * 路径即使协程被取消也能完成清理，避免规则残留。
     */
    private suspend fun teardownAllRootRules() {
        withContext(NonCancellable) {
            RootTetherHijacker.teardown()
            RootTproxyApplier.teardown()
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
            teardownAllRootRules()
            clearPersistedState(storage)
            storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
            // 进程死透了，清 runtime/{uuid}/（里面有 root:root 的 provider 缓存，app 删不动）
            runningSubscriptionId?.let { ProfileFileOps.cleanupRootRuntime(this@MishkaRootService, it) }
            ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Error, errorMessage = errorMsg, tunMode = currentSubmode.tunMode))
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
        storage.putString(StorageKeys.ROOT_SUBMODE_ACTIVE, "")
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
        ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopping, tunMode = currentSubmode.tunMode))
        dynamicNotification.stop()
        scope.launch(Dispatchers.IO) {
            val storage = PlatformStorage(this@MishkaRootService)
            val runningSubscriptionId = storage.getString(StorageKeys.ROOT_ACTIVE_SUBSCRIPTION_ID, "").ifEmpty { null }
            runner.stop()
            teardownAllRootRules()
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
        ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopping, tunMode = currentSubmode.tunMode))
        dynamicNotification.stop()
        scope.launch(Dispatchers.IO) {
            val storage = PlatformStorage(this@MishkaRootService)
            val runningSubscriptionId = storage.getString(StorageKeys.ROOT_ACTIVE_SUBSCRIPTION_ID, "").ifEmpty { null }
            runner.stop()
            teardownAllRootRules()
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
        const val EXTRA_SUBMODE = "submode"

        /**
         * Tile / BootReceiver 等内部入口不知道 submode，按 storage 里的 TUN_MODE
         * 推导并填入 Intent。外部（ProxyServiceController）则直接显式设置 EXTRA_SUBMODE。
         */
        private fun resolveSubmodeFromStorage(context: Context): String {
            return when (PlatformStorage(context).getString(StorageKeys.TUN_MODE, "vpn")) {
                "root_tproxy" -> "tproxy"
                else -> "tun"
            }
        }

        fun start(context: Context, subscriptionId: String? = null) {
            val intent = Intent(context, MishkaRootService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SUBMODE, resolveSubmodeFromStorage(context))
                subscriptionId?.let { putExtra(EXTRA_SUBSCRIPTION_ID, it) }
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MishkaRootService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_SUBMODE, resolveSubmodeFromStorage(context))
            }
            context.startService(intent)
        }
    }
}
