package top.yukonga.mishka.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import top.yukonga.mishka.MishkaApplication
import top.yukonga.mishka.R
import top.yukonga.mishka.data.repository.OverrideJsonStore
import top.yukonga.mishka.data.repository.SubscriptionRepositoryImpl
import top.yukonga.mishka.data.store.ProfileTransformWriter
import top.yukonga.mishka.domain.model.resolveExternalController
import top.yukonga.mishka.platform.AppListProvider
import top.yukonga.mishka.platform.BootSession
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.platform.ProxyServiceStatus
import top.yukonga.mishka.platform.ProxyState
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.TunMode
import top.yukonga.mishka.util.describe
import top.yukonga.mishka.viewmodel.AppProxyMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
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
     * 缺省（为空或未知）兜底为 Tun。
     */
    private enum class Submode(val storageValue: String, val tunMode: TunMode) {
        Tun("tun", TunMode.RootTun),
        Tproxy("tproxy", TunMode.RootTproxy);

        companion object {
            fun from(value: String?): Submode = entries.firstOrNull { it.storageValue == value } ?: Tun
        }
    }

    // 启停链全程阻塞：su fork + waitFor、iptables 退避 sleep、递归拷贝 runtime/ 沙箱。
    // 跑在 Default 上会长时间占住数个 CPU 池线程，与 Compose 重组、导入管线抢同一批核
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runner by lazy { MihomoRunner(this) }
    private val dynamicNotification by lazy {
        DynamicNotificationManager(this, scope, MishkaApplication.instance.connectionManager)
    }

    // 取 Koin 单例而非自建：store 的内存值是权威值，自建实例读不到 UI 侧刚落的设置
    private val overrideStore: OverrideJsonStore by inject()
    private val transformWriter: ProfileTransformWriter by inject()
    private val subscriptionRepository: SubscriptionRepositoryImpl by inject()
    private var monitorJob: Job? = null
    private var notificationRefreshJob: Job? = null

    // 本实例启动或 attach 时记下的 runtime 代次。停机清理必须用它，不能事后读全局代次，
    // 否则会认领别的启动刚建好的沙箱。
    @Volatile
    private var runtimeEpoch: Long = 0L

    @Volatile
    private var runtimeEpochBound: Boolean = false

    private class PreparedRuntime(val job: Job, val uuid: String, val epoch: Long)

    // 最近一次全新启动 prepare 出的沙箱，按启动协程认领。见 launchPostDeathCleanup。
    @Volatile
    private var preparedRuntime: PreparedRuntime? = null

    // 自动连接与补发 BOOT_COMPLETED 的 BootReceiver 会各发一次 ACTION_START，两条启动协程
    // 并发跑 iptables 会互抢 xtables.lock。@Volatile：主线程写，stop/restart 在 IO 协程读
    @Volatile
    private var startJob: Job? = null

    @Volatile
    private var startJobAttachOnly = false

    // onDestroy 不取消。进程已死后的回写放这里，等 processLock 时不会被 scope.cancel() 丢掉。
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val monitorLock = Any()
    private val monitorGeneration = AtomicInteger(0)

    @Volatile
    private var monitorCleanup: Job? = null

    @Volatile
    private var destroyed = false

    private var createdSerial = 0

    // 当前正在运行的 submode；由 onStartCommand 的 EXTRA_SUBMODE 设定
    @Volatile
    private var currentSubmode: Submode = Submode.Tun

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        createdSerial = bumpInstanceSerial()
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
                val subscriptionId = intent.getStringExtra(ProxyServiceController.EXTRA_SUBSCRIPTION_ID)
                // attach-only：reopen 触发的重连请求，只允许重连活着的进程，禁止全新启动
                val attachOnly = intent.getBooleanExtra(EXTRA_ATTACH_ONLY, false)
                startProxy(subscriptionId, attachOnly)
            }

            ACTION_STOP -> stopProxy()
            ACTION_RESTART -> {
                val subscriptionId = intent.getStringExtra(ProxyServiceController.EXTRA_SUBSCRIPTION_ID)
                restartProxy(subscriptionId)
            }
        }
        return START_STICKY
    }

    private fun isRootRunning(mode: TunMode): Boolean =
        mode == TunMode.RootTun || mode == TunMode.RootTproxy

    /**
     * 启动代理。**幂等**：已有启动协程在跑时忽略本次请求。唯一例外是 fresh START 抢占
     * 进行中的 attach-only——后者失败只保持停止，而 fresh 请求要求「必须跑起来」。
     */
    private fun startProxy(subscriptionId: String? = null, attachOnly: Boolean = false) {
        val inFlight = startJob?.takeIf { it.isActive }
        if (inFlight != null) {
            if (attachOnly || !startJobAttachOnly) {
                Log.i(TAG, "Start in progress, ignoring duplicate START (attachOnly=$attachOnly)")
                return
            }
            Log.i(TAG, "Fresh START supersedes in-flight attach-only start")
            inFlight.cancel()
        }
        startJobAttachOnly = attachOnly
        startJob = scope.launch {
            // 抢占时等被取消者收敛，避免两条协程同时跑 iptables
            inFlight?.join()
            val submode = currentSubmode
            val tunMode = submode.tunMode
            Log.i(TAG, "Starting proxy (ROOT $submode), subscription: $subscriptionId, attachOnly=$attachOnly")
            // 防御外部直拉 Service：无 config 时 mihomo 启动失败且 root 写的日志难以定位
            if (!ProfileFileOps.hasValidConfig(this@MishkaRootService, subscriptionId)) {
                Log.e(TAG, "No valid subscription config (id=$subscriptionId), aborting start")
                ProxyServiceBridge.updateState(
                    ProxyServiceStatus(
                        ProxyState.Error,
                        errorMessage = getString(R.string.error_no_active_profile),
                        tunMode = tunMode,
                    )
                )
                stopSelf()
                return@launch
            }
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
                Log.i(
                    TAG,
                    "Existing process pid=$existingPid runs subscription=$existingSubscriptionId, requested=$subscriptionId, restarting"
                )
            }
            if (tetherModeMismatch) {
                Log.i(
                    TAG,
                    "Tether mode changed while app was killed (active=$tetherModeActive, requested=$tetherModeRequested), restarting"
                )
            }
            if (submodeMismatch) {
                Log.i(TAG, "Submode changed while app was killed (active=$submodeActive, requested=${submode.storageValue}), restarting")
            }
            if (existingPid > 0 && existingSecret.isNotEmpty() && !subscriptionMismatch && !tetherModeMismatch && !submodeMismatch) {
                val ec = overrideStore.load().resolveExternalController()
                if (runner.attachToExisting(existingPid, existingSecret, ec, subscriptionId)) {
                    val existingStartTime =
                        storage.getString(StorageKeys.ROOT_START_TIME, "").toLongOrNull() ?: Clock.System.now().toEpochMilliseconds()
                    Log.i(TAG, "Reconnected to existing mihomo: pid=$existingPid submode=${submode.storageValue}")
                    val activeMode = RootTetherHijacker.Mode.from(tetherModeActive.ifEmpty { tetherModeRequested })
                    val tproxySupported = submode == Submode.Tun && activeMode == RootTetherHijacker.Mode.PROXY &&
                            RootTetherHijacker.probeTproxySupport()
                    if (!applyRootRules(storage, submode, tproxySupported, attach = true)) return@launch
                    ProxyServiceBridge.updateState(
                        ProxyServiceStatus(
                            ProxyState.Running,
                            secret = existingSecret,
                            externalController = ec,
                            tunMode = tunMode,
                            startTime = existingStartTime,
                            mihomoPid = runner.pid
                        )
                    )
                    dynamicNotification.startOrFallbackStatic(storage, tunMode)
                    storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "true")
                    // 重连到活着的 mihomo：它仍在 runtime/{uuid}/ 下跑，监控日志从同一目录读。
                    // 代次取重连当下的值；之后的 prepare 会递增，这次监控就不能再删沙箱。
                    if (subscriptionId != null) bindRuntimeEpoch(RootRuntimeCache.epochOf(subscriptionId))
                    val workDir = if (subscriptionId != null) ProfileFileOps.getRuntimeDir(
                        this@MishkaRootService,
                        subscriptionId
                    ) else ConfigGenerator.getWorkDir(this@MishkaRootService)
                    startProcessMonitor(workDir, subscriptionId)
                    return@launch
                }
                Log.i(TAG, "Existing process pid=$existingPid failed attach verification, cleaning up")
                clearPersistedState(storage)
            }

            // attach-only 请求（app reopen 触发的重连）：没有活着的进程可重连时，
            // 绝不回退到全新启动——用户未主动请求启动，只是打开了 app。清状态、置 Stopped 后退出。
            // 设备重启 / 进程崩溃后打开 app 会走到这里，避免"未开自动重启却自动跑起来"。
            if (attachOnly) {
                // 阻塞的进程/API 探测不会响应 cancel；先让位，避免 stopSelf 杀掉接替的启动。
                if (!isActive) {
                    Log.i(TAG, "Attach-only start superseded, leaving state to the new start")
                    return@launch
                }
                Log.i(TAG, "Attach-only reopen: no live mihomo to reconnect, staying stopped")
                RootHelper.cleanupOrphanedMihomo(
                    tunDevice = storage.getString(StorageKeys.ROOT_TUN_DEVICE, RuntimeOverrideBuilder.DEFAULT_TUN_DEVICE)
                )
                if (!teardownRootRulesOrReport(tunMode)) return@launch
                currentCoroutineContext().ensureActive()
                clearPersistedState(storage)
                storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
                ProxyServiceBridge.markStopped(tunMode)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            // 2. 清理残留进程（上次的进程已失效，确保干净启动）
            // 先停自身 runner（Service 实例被复用时可能仍持有旧状态），再 pkill 孤儿进程
            // 同时清理 TUN 接口防止下次启动 sing-tun EEXIST（silent failure 源头）
            // 监控若已离开 delay()，cancel 打断不了读日志，更停不掉已进入 NonCancellable 的回写。
            // 等它收敛后再 prepare，避免它随后 rm -rf 掉新沙箱，或 stopSelf 掉这次启动。
            joinSuperseded(supersedeMonitor())
            if (runner.isRunning) {
                runner.stop()
            }
            val currentTun = storage.getString(StorageKeys.ROOT_TUN_DEVICE, RuntimeOverrideBuilder.DEFAULT_TUN_DEVICE)
            RootHelper.cleanupOrphanedMihomo(tunDevice = currentTun)
            // 启动前把上一次残留的 iptables 规则（另一个 submode 可能还没清干净）彻底擦一遍
            if (!teardownRootRulesOrReport(tunMode)) return@launch
            // 上次停机若没来得及回写，这里补一次；进程已在上面杀掉
            RootRuntimeCache.releaseAll(this@MishkaRootService, subscriptionRepository)

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
            if (submode == Submode.Tproxy) {
                if (!RootTproxyApplier.probeTproxySupport()) {
                    storage.putString(StorageKeys.ROOT_TPROXY_KERNEL_CAPABLE, "false")
                    Log.e(TAG, "xt_TPROXY unsupported, cannot start ROOT TPROXY mode")
                    ProxyServiceBridge.updateState(
                        ProxyServiceStatus(ProxyState.Error, errorMessage = getString(R.string.error_tproxy_unsupported), tunMode = tunMode)
                    )
                    stopSelf()
                    return@launch
                }
                storage.putString(StorageKeys.ROOT_TPROXY_KERNEL_CAPABLE, "true")
            }

            // 3.5 准备 runtime/{uuid}/ 沙箱：从 imported/{uuid}/ 复制，mihomo 在此以 root 写入
            //     不污染 imported/，保证更新/删除始终在 app UID 下工作
            if (subscriptionId != null) {
                try {
                    val epoch = RootRuntimeCache.prepare(this@MishkaRootService, subscriptionId)
                    bindRuntimeEpoch(epoch)
                    preparedRuntime = PreparedRuntime(coroutineContext.job, subscriptionId, epoch)
                } catch (e: CancellationException) {
                    // 停止抢占了启动。吞掉会发布 Error 并 stopSelf，用户点停止却看到「启动失败」
                    throw e
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
            val userOverride = overrideStore.load()
            val secret = ConfigGenerator.resolveSecret(this@MishkaRootService, userOverride, subscriptionId)
            val extCtl = userOverride.resolveExternalController()
            // 仅 TUN submode 且用户选 PROXY 时才探测 xt_TPROXY 为 RootTetherHijacker 开 tproxy-port 入站；
            // 探测结果同步写入 StorageKeys.ROOT_TPROXY_KERNEL_CAPABLE，UI 据此决定是否显示降级告警
            val userWantsProxy = submode == Submode.Tun &&
                    tetherModeRequested == RootTetherHijacker.Mode.PROXY.storageValue
            val tproxyForTether = if (userWantsProxy) {
                val supported = RootTetherHijacker.probeTproxySupport()
                if (!supported) Log.w(TAG, "xt_TPROXY unavailable, PROXY degraded to userspace TUN")
                storage.putString(StorageKeys.ROOT_TPROXY_KERNEL_CAPABLE, if (supported) "true" else "false")
                supported
            } else {
                // 非 PROXY 路径不显示 TPROXY 告警；但 TPROXY submode 前面已写入探测结果，别清掉
                if (submode == Submode.Tun) {
                    storage.putString(StorageKeys.ROOT_TPROXY_KERNEL_CAPABLE, "")
                }
                false
            }
            val viaProxy = storage.getString(StorageKeys.SUBSCRIPTION_UPDATE_VIA_PROXY, "true") == "true"
            val transformPlan = try {
                prepareRuntimeTransform(this@MishkaRootService, subscriptionId, subscriptionRepository, transformWriter)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare subscription transform", e)
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
            val overrideFile = RuntimeOverrideBuilder.buildAndWriteForRun(
                context = this@MishkaRootService,
                userOverride = userOverride,
                tunFd = -1,
                tunMode = submode.tunMode,
                subscriptionUpdateViaProxy = viaProxy,
                subscriptionMixedPort = transformPlan.mixedPort,
                tproxyForTether = tproxyForTether,
            )
            try {
                RootDnsApplier.prepare(rootTetherInterfaces(storage))
            } catch (e: CancellationException) {
                withContext(NonCancellable) { teardownAllRootRules() }
                throw e
            } catch (e: Exception) {
                failRootRouting(storage, tunMode, e)
                return@launch
            }

            // 5. 以 root 启动 mihomo
            // age 加密订阅：runtime/ 保持密文，密钥与覆写选择来自同一份 imported DB 快照。
            val success = runner.start(
                subscriptionId = subscriptionId,
                useRoot = true,
                overrideJsonPath = overrideFile.absolutePath,
                secret = secret,
                externalController = extCtl,
                ageSecretKey = transformPlan.ageSecretKey,
                transformPath = transformPlan.transformPath,
                preferTransformMixedPort = transformPlan.transformPath != null && viaProxy && userOverride.mixedPort == null,
            )
            if (!success) {
                if (!teardownRootRulesOrReport(tunMode)) return@launch
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

            if (!applyRootRules(storage, submode, tproxyForTether, attach = false)) return@launch

            // 7. 更新状态和通知
            ProxyServiceBridge.updateState(
                ProxyServiceStatus(
                    ProxyState.Running,
                    secret = runner.secret,
                    externalController = extCtl,
                    tunMode = tunMode,
                    startTime = startTime,
                    mihomoPid = runner.pid
                )
            )
            dynamicNotification.startOrFallbackStatic(storage, tunMode)
            storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "true")
            Log.i(TAG, "Proxy running (ROOT $submode)")

            val workDir = if (subscriptionId != null) ProfileFileOps.getRuntimeDir(
                this@MishkaRootService,
                subscriptionId
            ) else ConfigGenerator.getWorkDir(this@MishkaRootService)
            startProcessMonitor(workDir, subscriptionId)
        }
    }

    private suspend fun applyRootRules(
        storage: PlatformStorage,
        submode: Submode,
        tproxyForTether: Boolean,
        attach: Boolean,
    ): Boolean {
        try {
            when (submode) {
                Submode.Tun -> if (!attach || shouldReapplyOnAttach(storage, RootTetherHijacker::anyRulesPresent)) {
                    if (attach) applyTetherRulesActive(storage, tproxyForTether) else applyTetherRules(storage, tproxyForTether)
                }

                Submode.Tproxy -> applyTproxyRules(storage)
            }
            val proxyTether = submode == Submode.Tproxy || RootTetherHijacker.Mode.from(
                storage.getString(StorageKeys.ROOT_TETHER_MODE, RootTetherHijacker.Mode.BYPASS.storageValue)
            ) == RootTetherHijacker.Mode.PROXY
            val allowedInterfaces = rootTetherInterfaces(storage)
            val interfaces = if (proxyTether) allowedInterfaces else emptyList()
            // DNS hook 必须排在数据转发 hook 前；attach 也重建整套 DNS 规则，不能只检查单个锚点。
            RootDnsApplier.apply(applicationInfo.uid, interfaces, allowedInterfaces)
            currentCoroutineContext().ensureActive()
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                runner.stop()
                teardownAllRootRules()
            }
            throw e
        } catch (e: Exception) {
            failRootRouting(storage, submode.tunMode, e)
            return false
        }
    }

    private fun rootTetherInterfaces(storage: PlatformStorage): List<String> = RootTetherHijacker.parseInterfaces(
        storage.getString(StorageKeys.ROOT_TETHER_IFACES, RootTetherHijacker.DEFAULT_IFACES)
    )

    private suspend fun failRootRouting(storage: PlatformStorage, tunMode: TunMode, error: Exception) {
        Log.e(TAG, "Failed to install ROOT routing", error)
        withContext(NonCancellable) {
            runner.stop()
            try {
                teardownAllRootRules()
            } catch (cleanupError: Exception) {
                Log.e(TAG, "ROOT routing cleanup failed", cleanupError)
                error.addSuppressed(cleanupError)
            }
            clearPersistedState(storage)
            storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
            ProxyServiceBridge.updateState(
                ProxyServiceStatus(
                    ProxyState.Error,
                    errorMessage = getString(R.string.error_generic_start_failed, error.describe()),
                    tunMode = tunMode,
                )
            )
            stopSelf()
        }
    }

    /**
     * ROOT TPROXY 规则装配：读 AppProxy 配置 + tether ifaces，把包名解析为 UID 后交给 Applier。
     *
     * IPv6 注入复用 [StorageKeys.VPN_ALLOW_IPV6]，与 VPN/ROOT TUN 的 `inet6-address` 同一开关。
     * 默认 false：跳过 ip6tables / `ip -6` 规则，IPv6 出站走内核原生主路由表，避免 mihomo
     * `ipv6: false` 时 TPROXY 重定向 → 拨号失败 → App 重试紧密循环造成的 mihomo 内存增长。
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
        val ipv6Enabled = storage.getString(StorageKeys.VPN_ALLOW_IPV6, "false") == "true"
        RootTproxyApplier.apply(appUid, selectedUids, proxyMode, ifaces, ipv6Enabled)
    }

    /**
     * 热点规则沿用条件重建；DNS 与 TPROXY 规则在 attach 时始终完整重建。
     * ROOT_ATTACH_FORCE_REAPPLY 只额外强制重建 ROOT TUN 的热点规则。
     */
    private fun shouldReapplyOnAttach(storage: PlatformStorage, probe: () -> Boolean): Boolean {
        val force = storage.getString(StorageKeys.ROOT_ATTACH_FORCE_REAPPLY, "false") == "true"
        if (force) {
            Log.i(TAG, "attach: force re-apply (ROOT_ATTACH_FORCE_REAPPLY=true)")
            return true
        }
        val present = probe()
        if (present) {
            Log.i(TAG, "attach: probe found existing rules, skip re-apply")
        } else {
            Log.i(TAG, "attach: probe found rules missing, will re-apply")
        }
        return !present
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
            check(RootDnsApplier.teardown()) { "Failed to remove ROOT DNS routing" }
        }
    }

    private suspend fun teardownRootRulesOrReport(tunMode: TunMode): Boolean {
        try {
            teardownAllRootRules()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "ROOT routing cleanup failed", e)
            ProxyServiceBridge.updateState(
                ProxyServiceStatus(ProxyState.Error, errorMessage = e.describe(), tunMode = tunMode)
            )
            stopServiceQuietly()
            return false
        }
    }

    /**
     * 进程死亡监测。ROOT 下 `runner.isRunning` 每轮 fork 一个 su（`/proc` 是 hidepid，
     * app 读不到 root 进程），5s 是「掉线多久被发现」与「每分钟 12 次 su」之间的取舍，
     * 不是可以随手往下调的普通轮询。
     */
    private suspend fun startProcessMonitor(workDir: File, subscriptionId: String?) {
        // 旧监控可能已在 NonCancellable 的 release 里。只 cancel 会让它在新沙箱建好后补删。
        joinSuperseded(supersedeMonitor())
        val generation = monitorGeneration.get()
        val ownedEpoch = runtimeEpoch
        val watchedId = subscriptionId
        val serial = createdSerial
        monitorJob = scope.launch {
            delay(10_000)
            while (runner.isRunning) {
                delay(5_000)
            }
            // 离开 delay() 之后读日志没有挂起点。监控已被 stop/restart 接手就不要再启动清理。
            val logContent = RootHelper.readLogFile(File(workDir, "mihomo.log").absolutePath)
            if (monitorGeneration.get() != generation) return@launch
            val errorMsg = if (logContent.isNotBlank()) {
                getString(R.string.error_mihomo_start_failed, logContent)
            } else {
                getString(R.string.error_mihomo_exited)
            }
            Log.e(TAG, "mihomo process died unexpectedly (ROOT): $errorMsg")
            // 进程已死。清理放到 onDestroy 取消不了的 scope；stop/restart 仍可 cancel 等锁阶段。
            val cleanup = cleanupScope.launch {
                if (!isCurrentInstance(serial)) return@launch
                if (!teardownRootRulesOrReport(currentSubmode.tunMode)) return@launch
                // 被接手、换代（删除订阅、切 VPN 的 releaseAll），或已有新 Service：
                // 不要改全局状态，也不要 stopSelf 掉新沙箱。
                if (!isActive || !isCurrentInstance(serial)) return@launch
                if (watchedId != null && RootRuntimeCache.epochOf(watchedId) != ownedEpoch) return@launch
                // 先报 Error 再回写：回写要等 processLock，订阅下载可能占它几分钟，
                // 期间界面不能还显示 Running。
                val storage = PlatformStorage(this@MishkaRootService)
                clearPersistedState(storage)
                storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
                ProxyServiceBridge.updateState(
                    ProxyServiceStatus(ProxyState.Error, errorMessage = errorMsg, tunMode = currentSubmode.tunMode)
                )
                dynamicNotification.stop()
                stopServiceQuietly()
                if (watchedId != null) {
                    RootRuntimeCache.release(this@MishkaRootService, watchedId, subscriptionRepository, ownedEpoch)
                }
            }
            synchronized(monitorLock) {
                if (monitorGeneration.get() != generation) {
                    cleanup.cancel()
                } else {
                    monitorCleanup = cleanup
                }
            }
            // 本协程可能已被 onDestroy cancel。这里仍要等清理结束，接手方的 join 才不会提前返回。
            withContext(NonCancellable) {
                cleanup.join()
            }
        }
    }

    private fun supersedeMonitor(): List<Job> = synchronized(monitorLock) {
        monitorGeneration.incrementAndGet()
        val jobs = listOfNotNull(monitorJob, monitorCleanup)
        monitorJob?.cancel()
        monitorCleanup?.cancel()
        monitorJob = null
        monitorCleanup = null
        jobs
    }

    private suspend fun joinSuperseded(jobs: List<Job>) {
        jobs.forEach { it.join() }
    }

    private fun isCurrentInstance(serial: Int): Boolean = instanceSerial == serial

    private fun stopServiceQuietly() {
        if (destroyed) return
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (e: Exception) {
            Log.w(TAG, "stop after destroy: ${e.message}")
        }
    }

    private fun bindRuntimeEpoch(epoch: Long) {
        runtimeEpoch = epoch
        runtimeEpochBound = true
    }

    private fun ownedRuntimeEpoch(): Long {
        if (runtimeEpochBound) return runtimeEpoch
        val id = PlatformStorage(this).getString(StorageKeys.ROOT_ACTIVE_SUBSCRIPTION_ID, "")
        return if (id.isEmpty()) 0L else RootRuntimeCache.epochOf(id)
    }

    private fun persistState(storage: PlatformStorage, secret: String, startTime: Long, subscriptionId: String?) {
        storage.putString(StorageKeys.ROOT_MIHOMO_PID, runner.pid.toString())
        storage.putString(StorageKeys.ROOT_MIHOMO_SECRET, secret)
        storage.putString(StorageKeys.ROOT_START_TIME, startTime.toString())
        storage.putString(StorageKeys.ROOT_ACTIVE_SUBSCRIPTION_ID, subscriptionId ?: "")
        // reopen 时据此识别设备是否重启过
        BootSession.mark(this, storage)
    }

    private fun clearPersistedState(storage: PlatformStorage) {
        storage.putString(StorageKeys.ROOT_MIHOMO_PID, "")
        storage.putString(StorageKeys.ROOT_MIHOMO_SECRET, "")
        storage.putString(StorageKeys.ROOT_START_TIME, "")
        storage.putString(StorageKeys.ROOT_ACTIVE_SUBSCRIPTION_ID, "")
        BootSession.clear(storage)
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
        val superseded = supersedeMonitor()
        ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopping, tunMode = currentSubmode.tunMode))
        dynamicNotification.stop()
        // 代次在 join 前记下。join 会等已进入 NonCancellable 的 release 跑完，不能改读它退休后的代次。
        val ownedEpoch = ownedRuntimeEpoch()
        launchPostDeathCleanup(superseded, ownedEpoch, afterRelease = { storage ->
            clearPersistedState(storage)
            if (destroyed) {
                storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
                return@launchPostDeathCleanup
            }
            withContext(Dispatchers.Main) {
                startProxy(subscriptionId)
            }
        })
    }

    private fun stopProxy() {
        Log.i(TAG, "Stopping proxy (ROOT)...")
        val superseded = supersedeMonitor()
        ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopping, tunMode = currentSubmode.tunMode))
        dynamicNotification.stop()
        val ownedEpoch = ownedRuntimeEpoch()
        // mihomo 已死、规则已撤就算停完。回写要等 processLock，订阅下载可能占它几分钟，
        // 「停止中」不能陪着等；回写没做完进程就被回收的话，下一次启动的 releaseAll 会补上。
        launchPostDeathCleanup(superseded, ownedEpoch, beforeRelease = { storage, confirmedDead ->
            // 没确认杀掉就保留 PID/secret 与 was-running 标记：清掉之后残留进程再也定位不到，
            // 下次 TOGGLE 还会把「有进程在跑」误判成「没在跑」而去启一个新的
            if (confirmedDead) {
                clearPersistedState(storage)
                storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
            }
            if (destroyed) return@launchPostDeathCleanup
            ProxyServiceBridge.markStopped(currentSubmode.tunMode)
            stopServiceQuietly()
        })
    }

    /**
     * 确认要停掉 mihomo 之后的回写。挂在 [cleanupScope]，onDestroy 的 cancel 丢不掉。
     * 新实例出现后不再改全局状态，避免把后一次启动的 PID 清掉。
     * [beforeRelease] 在杀进程、撤规则之后、等锁回写之前执行；[afterRelease] 等回写完成。
     */
    private fun launchPostDeathCleanup(
        superseded: List<Job>,
        ownedEpoch: Long,
        beforeRelease: suspend (PlatformStorage, Boolean) -> Unit = { _, _ -> },
        afterRelease: suspend (PlatformStorage) -> Unit = {},
    ) {
        val serial = createdSerial
        cleanupScope.launch {
            superseded.forEach { it.join() }
            // 用户在启动过程中按停止：先中止启动协程，避免它继续把状态写回 Running
            val start = startJob
            start?.cancelAndJoin()
            if (!isCurrentInstance(serial)) return@launch
            val storage = PlatformStorage(this@MishkaRootService)
            val runningSubscriptionId = storage.getString(StorageKeys.ROOT_ACTIVE_SUBSCRIPTION_ID, "").ifEmpty { null }
            // ownedEpoch 是调用方在 cancel 前记下的；被取消的启动若已 prepare，那份沙箱的代次更新，
            // 订阅 ID 也可能还没来得及持久化。按 job 认领，不读全局代次，免得认领别的启动。
            val prepared = preparedRuntime?.takeIf { start != null && it.job === start }
            val targets = buildMap {
                runningSubscriptionId?.let { put(it, ownedEpoch) }
                prepared?.let { put(it.uuid, it.epoch) }
            }
            // app 进程被杀后 root mihomo 仍存活，本实例的 runner 从没 start 也没 attach，
            // childPid 为空会让 stop() 整段跳过 —— 先按持久化 PID 认领，否则杀不掉还会清掉线索
            storage.getString(StorageKeys.ROOT_MIHOMO_PID, "").toIntOrNull()
                ?.let { runner.adoptForStop(it) }
            val confirmedDead = runner.stop()
            if (!teardownRootRulesOrReport(currentSubmode.tunMode)) return@launch
            beforeRelease(storage, confirmedDead)
            // 回写缓存后再清沙箱，下轮 start 从 imported/ 复制才能跳过未过期的 HTTP 拉取。
            // beforeRelease 可能已 stopSelf，新实例的 prepare 由沙箱代次挡住，不会被这里删掉。
            targets.forEach { (uuid, epoch) ->
                RootRuntimeCache.release(this@MishkaRootService, uuid, subscriptionRepository, epoch)
            }
            if (!isCurrentInstance(serial)) return@launch
            afterRelease(storage)
        }
    }

    override fun onDestroy() {
        destroyed = true
        notificationRefreshJob?.cancel()
        // 只停还在轮询的监控。已经确认进程死亡的回写在 cleanupScope，不能在这里 cancel。
        monitorJob?.cancel()
        dynamicNotification.stop()
        // 注意：onDestroy 不 kill mihomo，让它继续运行以便重连。
        // 也不在主线程 join 监控：已进入 NonCancellable 的 release 会活过 scope.cancel()，
        // 由沙箱代次保证它不会删掉下一个 Service 刚 prepare 的目录。
        ProxyServiceBridge.markStoppedUnlessError(currentSubmode.tunMode)
        scope.cancel()
        Log.i(TAG, "MishkaRootService destroyed")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MishkaRootService"

        @Volatile
        private var instanceSerial = 0

        private fun bumpInstanceSerial(): Int = synchronized(this) { ++instanceSerial }

        const val ACTION_START = "top.yukonga.mishka.ROOT_START"
        const val ACTION_STOP = "top.yukonga.mishka.ROOT_STOP"
        const val ACTION_RESTART = "top.yukonga.mishka.ROOT_RESTART"
        const val EXTRA_SUBMODE = "submode"
        const val EXTRA_ATTACH_ONLY = "attach_only"

        /** TunMode 对应的 [EXTRA_SUBMODE] 取值；VPN 模式不归本 Service 管，返回 null。 */
        fun submodeExtra(mode: TunMode): String? =
            Submode.entries.firstOrNull { it.tunMode == mode }?.storageValue
    }
}
