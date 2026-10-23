package top.yukonga.mishka.platform

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.yukonga.mishka.R
import top.yukonga.mishka.service.MishkaRootService
import top.yukonga.mishka.service.MishkaTunService
import java.io.File

enum class ProxyState {
    Stopped,
    Starting,
    Running,
    Stopping,
    Error,
}

enum class TunMode { Vpn, RootTun, RootTproxy }

data class ProxyServiceStatus(
    val state: ProxyState = ProxyState.Stopped,
    val secret: String = "",
    val externalController: String = "127.0.0.1:9090",
    val errorMessage: String = "",
    val tunMode: TunMode = TunMode.Vpn,
    val startTime: Long = 0L,
    val mihomoPid: Int = -1,
    // 发布方已自行 toast 过 errorMessage，UI 层不要重复提示
    val errorNotified: Boolean = false,
)

/** 外部 intent 请求的启停动作。 */
enum class ExternalProxyAction { Start, Stop, Toggle, Restart }

/**
 * 外部启停的结果。除 [ExternalProxyOutcome.NeedVpnPermission] 外都已在 Controller 内落地，调用方
 * 按结果播报即可；授权对话框要求 Activity 上下文，故把订阅 id 交回调用方自己拉。
 */
sealed interface ExternalProxyOutcome {
    data object Started : ExternalProxyOutcome
    data object Stopped : ExternalProxyOutcome
    data object Restarted : ExternalProxyOutcome
    data object AlreadyRunning : ExternalProxyOutcome

    /** 重启只在运行中成立 —— 界面上的重启按钮同样只在运行中出现。 */
    data object NotRunning : ExternalProxyOutcome
    data object Busy : ExternalProxyOutcome
    data object NoSubscription : ExternalProxyOutcome
    data class NeedVpnPermission(val subscriptionId: String) : ExternalProxyOutcome
}

class ProxyServiceController(private val context: Context) {

    private val storage by lazy { PlatformStorage(context) }

    // 见 verifyAndSyncState：自动连接每进程只消费一次
    private var launchAutoConnectConsumed = false

    // 不接外部 scope：Koin 的那个实例随进程存活，其余入口（Tile / BootReceiver /
    // VpnPermissionActivity）是只发 start/stop 的短命自建实例，两者都没有可级联取消的宿主
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    @Volatile
    private var pendingRestartJob: Job? = null

    val status: StateFlow<ProxyServiceStatus> = ProxyServiceBridge.state

    fun start(subscriptionId: String? = null) {
        val id = resolveStartSubscriptionId(subscriptionId) ?: return
        startValidated(id)
    }

    fun restart(subscriptionId: String? = null) {
        val id = resolveStartSubscriptionId(subscriptionId) ?: return
        restartValidated(id)
    }

    /** [id] 已过 [resolveStartSubscriptionId]，别再校验一次 —— 那是一次主线程磁盘 stat。 */
    private fun startValidated(id: String) {
        val mode = getTunMode()
        val intent = buildServiceIntent(mode, Op.Start).apply {
            putExtra(EXTRA_SUBSCRIPTION_ID, id)
        }
        context.startForegroundService(intent)
    }

    private fun restartValidated(id: String) {
        // 优先读 bridge：代理运行中时它反映实际 Service；否则读 storage（用户最新选择）
        val mode = activeModeOrStored()
        val intent = buildServiceIntent(mode, Op.Restart).apply {
            putExtra(EXTRA_SUBSCRIPTION_ID, id)
        }
        context.startService(intent)
    }

    fun stop() {
        val mode = activeModeOrStored()
        val intent = buildServiceIntent(mode, Op.Stop)
        context.startService(intent)
    }

    /**
     * 外部入口的启停决策：状态判定集中在这里，别在调用方另判一套。
     * 过渡态直接忽略 —— 再发一次 start 会与 Service 内进行中的那轮启动协程并发，现象是同一个
     * Service 里出现两条 `Starting proxy`。
     */
    fun applyExternalAction(action: ExternalProxyAction): ExternalProxyOutcome {
        val state = ProxyServiceBridge.state.value.state
        if (state == ProxyState.Starting || state == ProxyState.Stopping) {
            return ExternalProxyOutcome.Busy
        }
        val running = state == ProxyState.Running
        when (action) {
            // 无条件发 STOP，不做「已经停了就跳过」的短路：ROOT 模式下 app 可能与仍在跑的 mihomo
            // 进程失联（bridge 停在 Stopped），跳过等于把「停掉残留进程」这条能力静默拿掉
            ExternalProxyAction.Stop -> {
                stop()
                return ExternalProxyOutcome.Stopped
            }

            // START 只认 bridge：真的没跑时它内部还要 attach 复用存活进程，这里提前判活会挡掉那条路径
            ExternalProxyAction.Start ->
                if (running) return ExternalProxyOutcome.AlreadyRunning

            ExternalProxyAction.Toggle ->
                if (running || rootProcessLikelyAlive()) {
                    stop()
                    return ExternalProxyOutcome.Stopped
                }

            // 未运行不顺手拉起：界面上的重启按钮同样只在运行中出现，外部入口保持同一语义。
            // ROOT 残留进程例外：app 进程死过一轮后 bridge 停在 Stopped 而 mihomo 仍在跑，
            // 此时重启才是调用方要的，判 NotRunning 会让这个残留进程彻底无人处理
            ExternalProxyAction.Restart ->
                if (!running && !rootProcessLikelyAlive()) return ExternalProxyOutcome.NotRunning
        }
        val id = resolveStartSubscriptionId() ?: return ExternalProxyOutcome.NoSubscription
        if (!hasVpnPermission()) return ExternalProxyOutcome.NeedVpnPermission(id)
        if (action == ExternalProxyAction.Restart) {
            restartValidated(id)
            return ExternalProxyOutcome.Restarted
        }
        startValidated(id)
        return ExternalProxyOutcome.Started
    }

    /**
     * ROOT 模式下 bridge 的 Stopped 不代表没在跑：app 进程被杀过时 root mihomo 仍在运行，bridge
     * 回到 Stopped。只看 bridge 会让 TOGGLE 又拉起一次而不是关掉它。
     *
     * 判据与 [verifyAndSyncState] 的 attach 前置一致（同一 boot session + 持久化 PID 非空）：
     * 设备重启会杀掉 root 进程，但 PID 字符串还留在 storage 里，只凭它非空会把重启后的首次
     * TOGGLE 误判成「在跑」。真正的存活确认在 Service 的 attach 三重校验里，这里只做启发式。
     */
    private fun rootProcessLikelyAlive(): Boolean {
        if (getTunMode() == TunMode.Vpn) return false
        if (storage.getString(StorageKeys.SERVICE_WAS_RUNNING, "false") != "true") return false
        if (storage.getString(StorageKeys.ROOT_MIHOMO_PID, "").isEmpty()) return false
        return !BootSession.hasRebootedSince(context, storage)
    }

    /**
     * 「配置变了，代理在跑就重启让它生效」的统一入口。决策必须基于 [ProxyServiceBridge] 而非
     * 调用方的 UI 标志——后者在启动窗口（约 10s）内仍是「未运行」，据此决策会漏掉重启，表现
     * 成「界面已是新配置、代理仍跑旧的」。
     *
     * 过渡态直接重启会与 Service 内进行中的启动协程并发，故挂起等状态收敛：落到 Running 才补
     * 一次，落到 Stopped / Error 就放弃——用户中途手动停了代理，不该被这次重启又拉起来。
     */
    @Synchronized
    fun restartWhenReady(subscriptionId: String? = null) {
        pendingRestartJob?.cancel()
        pendingRestartJob = null
        when (ProxyServiceBridge.state.value.state) {
            ProxyState.Running -> restart(subscriptionId)

            ProxyState.Starting, ProxyState.Stopping -> {
                pendingRestartJob = scope.launch {
                    val settled = ProxyServiceBridge.state.first {
                        it.state != ProxyState.Starting && it.state != ProxyState.Stopping
                    }
                    if (settled.state != ProxyState.Running) return@launch
                    // 这条协程不在任何 UI 宿主下，异常逸出会走默认 handler 打崩进程
                    runCatching { restart(subscriptionId) }
                        .onFailure { Log.e(TAG, "pending restart failed", it) }
                }
            }

            ProxyState.Stopped, ProxyState.Error -> {
                // 没有在跑的代理：下次启动自然读到新配置
            }
        }
    }

    /** 重启的前提已消失（如订阅被删光、没有配置可切）时放弃挂起中的那次。 */
    @Synchronized
    fun cancelPendingRestart() {
        pendingRestartJob?.cancel()
        pendingRestartJob = null
    }

    /**
     * 订阅内容刚被刷新后请求生效。mihomo 只在进程启动时读一次 config.yaml，embed mode 下又没有
     * 热重载 API（`/configs`、`/restart` 全 404），重启进程是唯一手段。
     *
     * active 判定收在这里而不是各调用方：前台手边是 DB、后台 Worker 只有 storage，两套判定源
     * 迟早会不一致。
     */
    fun restartAfterProfileUpdate(uuid: String) {
        if (storage.getString(StorageKeys.RESTART_AFTER_PROFILE_UPDATE, "true") != "true") return
        if (uuid != storage.getString(StorageKeys.ACTIVE_PROFILE_UUID, "")) return
        restartWhenReady(uuid)
    }

    /**
     * app reopen 时对 ROOT 模式的重连入口（attach-only）：只尝试重连仍存活的 mihomo 进程，
     * attach 失败时 Service 端不会回退到全新启动（与 [start] 语义不同）。用户未主动请求启动，
     * 仅是打开了 app，因此进程若已不存在必须保持停止，而非自动跑起来。
     */
    fun reattachRoot() {
        val mode = getTunMode()
        if (mode != TunMode.RootTun && mode != TunMode.RootTproxy) return
        val id = storage.getString(StorageKeys.ACTIVE_PROFILE_UUID, "").ifEmpty { null } ?: run {
            storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
            return
        }
        val intent = buildServiceIntent(mode, Op.Start).apply {
            putExtra(EXTRA_SUBSCRIPTION_ID, id)
            putExtra(MishkaRootService.EXTRA_ATTACH_ONLY, true)
        }
        context.startForegroundService(intent)
    }

    /**
     * 启动前的订阅校验统一入口。校验失败时 toast、emit Bridge.Error，并在代理仍 Running 时
     * 同步发 STOP 让状态自洽。这里直接 toast 是为了覆盖 Tile / 通知这类没有 HomeViewModel
     * 在场的入口；`errorNotified` 让 UI 层跳过同一条消息，避免前台时弹两次。
     */
    fun resolveStartSubscriptionId(subscriptionId: String? = null): String? {
        startableSubscriptionId(subscriptionId)?.let { return it }

        val msg = noActiveProfileMessage()
        showToast(msg, long = true)
        val mode = activeModeOrStored()
        val wasRunning = ProxyServiceBridge.state.value.state == ProxyState.Running
        ProxyServiceBridge.updateState(
            ProxyServiceStatus(
                state = ProxyState.Error,
                errorMessage = msg,
                tunMode = mode,
                errorNotified = true,
            )
        )
        if (wasRunning) {
            context.startService(buildServiceIntent(mode, Op.Stop))
        }
        // 防止 Boot/升级路径在订阅缺失下被 BootReceiver 反复触发
        storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
        return null
    }

    /**
     * 纯校验版：active 订阅存在且 config.yaml 已落盘时返回其 uuid，否则 null。无任何副作用，
     * 供自动连接这类「不该因缺订阅打断用户」的静默路径使用。
     */
    /** 是否还有可启动的 active 订阅。无副作用，供「删光后该停还是该重启」这类决策使用。 */
    fun hasStartableSubscription(): Boolean = startableSubscriptionId() != null

    private fun startableSubscriptionId(subscriptionId: String? = null): String? {
        val effective = subscriptionId
            ?: storage.getString(StorageKeys.ACTIVE_PROFILE_UUID, "").ifEmpty { null }
            ?: return null
        val config = File(context.filesDir, "mihomo/imported/$effective/config.yaml")
        return effective.takeIf { config.isFile && config.length() > 0 }
    }

    private fun noActiveProfileMessage(): String =
        context.getString(R.string.error_no_active_profile)

    /**
     * 解析当前应该操作的 TunMode：
     * - 代理正在 Running/Starting/Stopping 时，用 bridge 的 tunMode（对应实际在跑的 Service）
     * - 否则用 storage 里的当前选择（用户可能刚改但还没启动）
     */
    private fun activeModeOrStored(): TunMode {
        val bridge = ProxyServiceBridge.state.value
        return if (bridge.state != ProxyState.Stopped && bridge.state != ProxyState.Error) {
            bridge.tunMode
        } else {
            getTunMode()
        }
    }

    private enum class Op { Start, Restart, Stop }

    /**
     * 组装指向目标 Service 的 Intent。ROOT_TUN / ROOT_TPROXY 共用 MishkaRootService，
     * 通过 EXTRA_SUBMODE 区分内部分支。
     *
     * 类与 action / extra 一律取符号而非字面量：重命名时字面量不会编译报错，故障表现是
     * 「attach-only 静默退化成全新启动」——正好是最不该发生的那件事。
     */
    private fun buildServiceIntent(mode: TunMode, op: Op): Intent = when (mode) {
        TunMode.Vpn -> Intent(context, MishkaTunService::class.java).setAction(
            when (op) {
                Op.Start -> MishkaTunService.ACTION_START
                Op.Restart -> MishkaTunService.ACTION_RESTART
                Op.Stop -> MishkaTunService.ACTION_STOP
            }
        )

        TunMode.RootTun, TunMode.RootTproxy -> Intent(context, MishkaRootService::class.java).apply {
            action = when (op) {
                Op.Start -> MishkaRootService.ACTION_START
                Op.Restart -> MishkaRootService.ACTION_RESTART
                Op.Stop -> MishkaRootService.ACTION_STOP
            }
            MishkaRootService.submodeExtra(mode)?.let { putExtra(MishkaRootService.EXTRA_SUBMODE, it) }
        }
    }

    // 由宿主 Activity（MainActivity）在 onCreate 注册后注入，替代已弃用的 startActivityForResult
    private var vpnPermissionLauncher: ActivityResultLauncher<Intent>? = null

    fun setVpnPermissionLauncher(launcher: ActivityResultLauncher<Intent>?) {
        vpnPermissionLauncher = launcher
    }

    fun requestVpnPermission() {
        if (getTunMode() != TunMode.Vpn) return
        // prepare 返回 null 表示已授权，无需弹窗
        val intent = VpnService.prepare(context) ?: return
        vpnPermissionLauncher?.launch(intent)
    }

    fun hasVpnPermission(): Boolean {
        if (getTunMode() != TunMode.Vpn) return true
        return VpnService.prepare(context) == null
    }

    fun hasRootPermission(): Boolean {
        return storage.getString(StorageKeys.HAS_ROOT, "false") == "true"
    }

    fun getTunMode(): TunMode {
        return when (storage.getString(StorageKeys.TUN_MODE, "vpn")) {
            "root_tun" -> TunMode.RootTun
            "root_tproxy" -> TunMode.RootTproxy
            else -> TunMode.Vpn
        }
    }

    fun verifyAndSyncState() {
        // 「打开应用时自动连接」是每次冷启动一次的意图：controller 由 Koin 以 single 提供，
        // 标记随进程存活，因此回前台的后续 onResume 不再触发——否则用户手动停止后切回来又被拉起
        val autoConnect = !launchAutoConnectConsumed &&
                storage.getString(StorageKeys.AUTO_CONNECT_ON_LAUNCH, "false") == "true"
        launchAutoConnectConsumed = true

        val wasRunning = storage.getString(StorageKeys.SERVICE_WAS_RUNNING, "false") == "true"
        val bridgeState = ProxyServiceBridge.state.value.state

        // bridge 认为在运行，但 VPN 权限已丢失（被其他 VPN 顶替），纠正状态
        if (bridgeState == ProxyState.Running || bridgeState == ProxyState.Starting) {
            if (getTunMode() == TunMode.Vpn && !hasVpnPermission()) {
                storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
                ProxyServiceBridge.markStopped(activeModeOrStored())
            }
            return
        }

        // 卡在 Stopping 状态（异步块被取消的残留场景），直接重置
        if (bridgeState == ProxyState.Stopping) {
            storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
            ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopped))
            return
        }

        val currentMode = getTunMode()
        // ROOT 模式：app 被杀但设备未重启时，mihomo root 进程仍存活，重新打开 app 时尝试
        // attach-only 重连（绝不全新启动）。但设备重启会杀死 root 进程，此时持久化的 PID 已
        // 过期——仅凭"PID 字符串非空"会误判为"进程仍活"而自动全新启动（用户未开启开机自启
        // 却看到代理自动跑起来的根源）。重启识别见 [BootSession]。
        if (wasRunning && (currentMode == TunMode.RootTun || currentMode == TunMode.RootTproxy)) {
            val hasPid = storage.getString(StorageKeys.ROOT_MIHOMO_PID, "").isNotEmpty()
            val rebooted = BootSession.hasRebootedSince(context, storage)
            if (hasPid && !rebooted) {
                // 同一 boot session 内且有持久化 PID：可能仍存活，交给 Service 做三重存活校验重连。
                // 自动连接开启时改走 start()——它同样先尝试 attach 复用存活进程，区别只在 attach
                // 不成时允许全新启动，而用户已表达「打开应用就要连上」的意图
                if (autoConnect) launchAutoConnect() else reattachRoot()
                return
            }
            // 设备已重启 / 无 PID 可重连：清掉过期状态，保持停止
            storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
            storage.putString(StorageKeys.ROOT_MIHOMO_PID, "")
            BootSession.clear(storage)
            if (autoConnect) launchAutoConnect()
            return
        }

        if (wasRunning && currentMode == TunMode.Vpn) {
            storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
        }
        if (autoConnect) launchAutoConnect()
    }

    /**
     * 冷启动时按用户设置自动连接。无可用订阅时静默返回——用户只是打开了 app，不该用错误 toast
     * 打断；VPN 缺授权时弹系统授权框，授权回调（MainActivity 的 launcher）会重新发起启动。
     */
    private fun launchAutoConnect() {
        val id = startableSubscriptionId() ?: return
        if (getTunMode() == TunMode.Vpn && !hasVpnPermission()) {
            requestVpnPermission()
            return
        }
        start(id)
    }

    companion object {
        private const val TAG = "ProxyServiceController"
        const val EXTRA_SUBSCRIPTION_ID = "subscription_id"
    }
}
