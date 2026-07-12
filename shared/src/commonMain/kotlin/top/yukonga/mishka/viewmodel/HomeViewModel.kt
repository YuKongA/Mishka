package top.yukonga.mishka.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import top.yukonga.mishka.data.api.MihomoConnectionManager
import top.yukonga.mishka.data.model.MihomoConfig
import top.yukonga.mishka.data.model.ProvidersResponse
import top.yukonga.mishka.data.model.Subscription
import top.yukonga.mishka.data.model.SubscriptionInfo
import top.yukonga.mishka.data.model.TunOverride
import top.yukonga.mishka.data.repository.MihomoRepository
import top.yukonga.mishka.data.repository.OverrideJsonStore
import top.yukonga.mishka.platform.PlatformSystemInfo
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.platform.ProxyState
import top.yukonga.mishka.platform.TunMode
import top.yukonga.mishka.util.FormatUtils
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/** 低频状态：mihomo 运行状态、配置、代理组、延迟、错误等；改变频率与生命周期事件相当 */
@Immutable
data class HomeUiState(
    val isRunning: Boolean = false,
    val isStarting: Boolean = false,
    val isStopping: Boolean = false,
    val mode: String = "--",
    val tunStack: String = "",
    val tunMode: TunMode = TunMode.Vpn,
    val ipv6: Boolean = false,
    val config: MihomoConfig? = null,
    val subscription: SubscriptionInfo? = null,
    val latencyBaidu: Int = -1,
    val latencyCloudflare: Int = -1,
    val latencyGoogle: Int = -1,
    val isTestingLatency: Boolean = false,
    val proxyGroups: List<String> = emptyList(),
    val selectedProxyGroup: String = "GLOBAL",
    val version: String = "",
    val errorMessage: String = "",
    val needsVpnPermission: Boolean = false,
)

/** 高频流量快照：每 100–500ms 更新，独立 Flow 隔离重组 */
@Immutable
data class SpeedSnapshot(
    val uploadSpeed: String = "-- B/s",
    val downloadSpeed: String = "-- B/s",
)

/** 高频内存快照 */
@Immutable
data class MemorySnapshot(
    val ramUsage: String = "-- MB",
    val ramTotal: String = "-- MB",
)

/** 系统信息快照：网卡 + CPU，2s 一次 */
@Immutable
data class SystemInfoSnapshot(
    val localIp: String = "0.0.0.0",
    val interfaceName: String = "--",
    val cpuUsage: String = "--%",
)

class HomeViewModel(
    private val serviceController: ProxyServiceController,
    private val overrideStore: OverrideJsonStore,
    private val connectionManager: MihomoConnectionManager,
    private val getActiveSubscriptionId: () -> String? = { null },
    private val activeSubscription: StateFlow<Subscription?> = MutableStateFlow(null).asStateFlow(),
    private val onLiveProviderInfo: (subscriptionId: String?, info: SubscriptionInfo?) -> Unit = { _, _ -> },
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _speedState = MutableStateFlow(SpeedSnapshot())
    val speedState: StateFlow<SpeedSnapshot> = _speedState.asStateFlow()

    private val _memoryState = MutableStateFlow(MemorySnapshot())
    val memoryState: StateFlow<MemorySnapshot> = _memoryState.asStateFlow()

    private val _systemInfoState = MutableStateFlow(SystemInfoSnapshot())
    val systemInfoState: StateFlow<SystemInfoSnapshot> = _systemInfoState.asStateFlow()

    // 秒；-1 表示尚未启动/已重置（UI 层格式化时转为空串）
    private val _uptimeState = MutableStateFlow(-1L)
    val uptimeState: StateFlow<Long> = _uptimeState.asStateFlow()

    private var repository: MihomoRepository? = null
    private var trafficJob: Job? = null
    private var memoryJob: Job? = null
    private var systemInfoJob: Job? = null
    private var runtimeConfigJob: Job? = null
    private var startTime: Long = 0
    private var uptimeJob: Job? = null
    private var mihomoPid: Int = -1
    private val systemInfo = PlatformSystemInfo()

    // 订阅切换发生在代理 Starting 窗口内时挂起，等状态切到 Running 再重启（见 onActiveSubscriptionChanged）
    private var pendingRestartOnRunning = false

    init {
        // 状态机仅维护 UI 状态字段（isStarting / isRunning / startTime / mihomoPid / errorMessage）
        // mihomo 客户端实例由 connectionManager 统一持有，HomeViewModel 不再自建
        viewModelScope.launch {
            serviceController.status.collect { status ->
                when (status.state) {
                    ProxyState.Starting -> {
                        _uiState.value = _uiState.value.copy(
                            isStarting = true,
                            isStopping = false,
                            tunMode = status.tunMode,
                            subscription = activeSubscriptionInfo(),
                        )
                    }

                    ProxyState.Running -> {
                        _uiState.value = _uiState.value.copy(
                            isStarting = false,
                            isRunning = true,
                            tunMode = status.tunMode,
                            subscription = activeSubscriptionInfo(),
                        )
                        startTime = if (status.startTime > 0) status.startTime else Clock.System.now().toEpochMilliseconds()
                        mihomoPid = status.mihomoPid
                        // 启动窗口内切过订阅：此刻代理已就绪，安全地重启切到新 active（不与启动协程并发）
                        if (pendingRestartOnRunning) {
                            pendingRestartOnRunning = false
                            restartProxy()
                        }
                    }

                    ProxyState.Stopping -> {
                        _uiState.value = _uiState.value.copy(isRunning = false, isStopping = true)
                    }

                    ProxyState.Stopped -> {
                        _uiState.value = HomeUiState()
                        resetHotStates()
                    }

                    ProxyState.Error -> {
                        _uiState.value = HomeUiState(errorMessage = status.errorMessage)
                        resetHotStates()
                    }
                }
            }
        }
        // 订阅共享 connectionManager.repository：非 null 表示 mihomo Running，触发数据收集；
        // null 表示已停止，cancel 流并重置数据。close 由 manager 负责，此处不调用。
        viewModelScope.launch {
            connectionManager.repository.collect { repo ->
                repository = repo
                if (repo != null) {
                    connectToMihomo()
                } else {
                    disconnectStreams()
                }
            }
        }
        // activeSubscription 变化的两个驱动源：① 用户切 active 订阅 ② Repository merge 进
        // mihomo live provider 后 emit 新值。两种情况主页流量栏都需要即时刷新。
        viewModelScope.launch {
            activeSubscription.collect {
                if (_uiState.value.isRunning || _uiState.value.isStarting) {
                    _uiState.value = _uiState.value.copy(subscription = activeSubscriptionInfo())
                }
            }
        }
    }

    private fun connectToMihomo() {
        // 立即用 DB 缓存填充流量栏；loadConfig 拿到 mihomo providers 后会通过 Repository
        // emit 触发 activeSubscription collect 自动覆盖为聚合后的实时数据
        _uiState.value = _uiState.value.copy(subscription = activeSubscriptionInfo())
        startTrafficCollection()
        startMemoryCollection()
        startSystemInfoCollection()
        startRuntimeConfigRefresh()
        startUptimeCounter()
        viewModelScope.launch {
            loadConfig()
            loadProxyGroups()
            testLatency()
        }
    }

    private suspend fun loadConfig() {
        repository?.getConfig()?.onSuccess { config ->
            _uiState.value = _uiState.value.copy(
                isRunning = true,
                mode = config.mode,
                tunStack = config.tun?.stack ?: "",
                ipv6 = config.ipv6,
                config = config,
            )
        }
        repository?.getVersion()?.onSuccess { version ->
            _uiState.value = _uiState.value.copy(version = version.version)
        }
        // 把聚合后的 provider info 推回 Repository，由 Repository 合并到 active Subscription
        // 视图模型。订阅页和主页都从 SubscriptionRepository.subscriptions 读，自动一致。
        repository?.getProviders()?.onSuccess { providers ->
            onLiveProviderInfo(getActiveSubscriptionId(), aggregateProviderInfo(providers))
        }
    }

    /**
     * 多订阅源（yaml 含多个 proxy-provider）时聚合所有有 subscription-userinfo header
     * 的 provider：流量求和、过期时间取最近。单源场景退化为单值；全部无 userinfo 返回 null
     * 让 Repository 走 DB 回退（File 类型订阅或服务端不返回 header 时）。
     */
    private fun aggregateProviderInfo(providers: ProvidersResponse): SubscriptionInfo? {
        val valid = providers.providers.values.mapNotNull { info ->
            info.subscriptionInfo?.takeIf { it.Total > 0 }
        }
        if (valid.isEmpty()) return null
        if (valid.size == 1) return valid.first()
        return SubscriptionInfo(
            Upload = valid.sumOf { it.Upload },
            Download = valid.sumOf { it.Download },
            Total = valid.sumOf { it.Total },
            Expire = valid.filter { it.Expire > 0 }.minOfOrNull { it.Expire } ?: 0,
        )
    }

    /**
     * 当前活跃订阅的视图流量信息。Repository 已合并 mihomo runtime live data 到 active
     * Subscription，本函数只做 model 转换；total<=0 返回 null 让 UI 显示 "--"。
     */
    private fun activeSubscriptionInfo(): SubscriptionInfo? {
        val sub = activeSubscription.value ?: return null
        if (sub.total <= 0) return null
        return SubscriptionInfo(
            Upload = sub.upload,
            Download = sub.download,
            Total = sub.total,
            Expire = sub.expire,
        )
    }

    private fun startTrafficCollection() {
        trafficJob?.cancel()
        trafficJob = viewModelScope.launch {
            repository?.trafficFlow()
                ?.catch { /* 连接断开 */ }
                ?.collect { traffic ->
                    _speedState.value = SpeedSnapshot(
                        uploadSpeed = FormatUtils.formatSpeed(traffic.up),
                        downloadSpeed = FormatUtils.formatSpeed(traffic.down),
                    )
                    if (!_uiState.value.isRunning) {
                        _uiState.value = _uiState.value.copy(isRunning = true)
                    }
                }
        }
    }

    private fun startMemoryCollection() {
        memoryJob?.cancel()
        memoryJob = viewModelScope.launch {
            repository?.memoryFlow()
                ?.catch { /* 连接断开 */ }
                ?.collect { memory ->
                    _memoryState.value = MemorySnapshot(
                        ramUsage = FormatUtils.formatBytes(memory.inuse),
                        ramTotal = if (memory.oslimit > 0) FormatUtils.formatBytes(memory.oslimit) else "-- MB",
                    )
                }
        }
    }

    private fun startUptimeCounter() {
        uptimeJob?.cancel()
        uptimeJob = viewModelScope.launch {
            while (true) {
                val elapsed = (Clock.System.now().toEpochMilliseconds() - startTime) / 1000
                _uptimeState.value = elapsed
                delay(1000.milliseconds)
            }
        }
    }

    private fun startSystemInfoCollection() {
        systemInfoJob?.cancel()
        systemInfoJob = viewModelScope.launch {
            while (true) {
                val networkInfo = systemInfo.getNetworkInfo()
                val cpu = systemInfo.getCpuUsage(mihomoPid)
                _systemInfoState.value = SystemInfoSnapshot(
                    localIp = networkInfo.localIp,
                    interfaceName = networkInfo.interfaceName,
                    cpuUsage = if (cpu >= 0) "${cpu.toInt()}%" else "--%",
                )
                delay(2000.milliseconds)
            }
        }
    }

    private fun startRuntimeConfigRefresh() {
        runtimeConfigJob?.cancel()
        runtimeConfigJob = viewModelScope.launch {
            while (true) {
                delay(2000.milliseconds)
                refreshRuntimeConfig()
            }
        }
    }

    private suspend fun refreshRuntimeConfig() {
        repository?.getConfig()?.onSuccess { config ->
            val current = _uiState.value
            if (!current.isRunning || current.config == config) return@onSuccess
            _uiState.value = current.copy(
                mode = config.mode,
                tunStack = config.tun?.stack ?: "",
                ipv6 = config.ipv6,
                config = config,
            )
        }
    }

    private fun resetHotStates() {
        _speedState.value = SpeedSnapshot()
        _memoryState.value = MemorySnapshot()
        _systemInfoState.value = SystemInfoSnapshot()
        _uptimeState.value = -1L
        // 代理已停止/出错：丢弃挂起的切换重启，避免下次启动到 Running 时触发意外重启
        pendingRestartOnRunning = false
    }

    fun startProxy() {
        if (!serviceController.hasVpnPermission()) {
            _uiState.value = _uiState.value.copy(needsVpnPermission = true)
            serviceController.requestVpnPermission()
            return
        }
        serviceController.start(getActiveSubscriptionId())
    }

    fun stopProxy() {
        serviceController.stop()
    }

    fun restartProxy() {
        serviceController.restart(getActiveSubscriptionId())
    }

    /**
     * 订阅切换后调用：把运行中的代理切到新 active 订阅。基于权威的 `serviceController.status`
     * （ProxyServiceBridge）状态决策，而非滞后的 `uiState.isRunning`——后者在代理 Starting
     * 窗口（启动后约 10s）内仍为 false，会漏掉重启导致「界面显示新订阅、代理仍跑旧订阅」。
     * Starting/Stopping 过渡态先记挂起标志，待状态切到 Running 再重启，避免在 Service 内与
     * 启动中的协程并发重启产生竞态。
     */
    fun onActiveSubscriptionChanged() {
        when (serviceController.status.value.state) {
            ProxyState.Running -> restartProxy()
            ProxyState.Starting, ProxyState.Stopping -> pendingRestartOnRunning = true
            ProxyState.Stopped, ProxyState.Error -> {
                // 无运行中代理：下次手动点"启动"会用新 active 订阅，无需处理
            }
        }
    }

    fun switchMode(mode: String) {
        val current = overrideStore.load()
        overrideStore.save(current.copy(mode = mode))
        _uiState.value = _uiState.value.copy(mode = mode)
        serviceController.restart(getActiveSubscriptionId())
    }

    fun switchTunStack(stack: String) {
        // TPROXY 模式 tun.enable=false，切 stack 无意义（且不应触发 restart）
        if (_uiState.value.tunMode == TunMode.RootTproxy) return
        val current = overrideStore.load()
        val nextTun = (current.tun ?: TunOverride()).copy(stack = stack)
        overrideStore.save(current.copy(tun = nextTun))
        _uiState.value = _uiState.value.copy(tunStack = stack)
        serviceController.restart(getActiveSubscriptionId())
    }

    private suspend fun loadProxyGroups() {
        repository?.getGroups()?.onSuccess { response ->
            // 从 GLOBAL 组的 all 字段获取配置文件中的原始顺序
            val globalGroup = response.proxies.firstOrNull { it.name == "GLOBAL" }
            val orderMap = globalGroup?.all
                ?.mapIndexed { index, name -> name to index }
                ?.toMap() ?: emptyMap()

            val sortedProxies = response.proxies
                .filter { it.name != "GLOBAL" }
                .sortedBy { orderMap[it.name] ?: Int.MAX_VALUE }

            val groupNames = sortedProxies.map { it.name }
            val defaultGroup = sortedProxies
                .firstOrNull {
                    it.type.equals("Selector", true) &&
                            it.name.contains(Regex("(?i)proxy|代理|节点"))
                }?.name
                ?: sortedProxies.firstOrNull { it.type.equals("Selector", true) }?.name
                ?: sortedProxies.firstOrNull { it.type.equals("URLTest", true) }?.name
                ?: "GLOBAL"
            _uiState.value = _uiState.value.copy(
                proxyGroups = groupNames,
                selectedProxyGroup = defaultGroup,
            )
        }
    }

    fun switchProxyGroup(group: String) {
        _uiState.value = _uiState.value.copy(selectedProxyGroup = group)
    }

    fun reloadConfig() {
        serviceController.restart(getActiveSubscriptionId())
    }

    fun testLatency() {
        if (_uiState.value.isTestingLatency) return
        if (repository == null) return
        _uiState.value = _uiState.value.copy(isTestingLatency = true)

        val proxyGroup = _uiState.value.selectedProxyGroup

        viewModelScope.launch {
            try {
                // Baidu 用 DIRECT 测直连延迟
                val baiduJob = launch {
                    repository?.getProxyDelay("DIRECT", "http://www.baidu.com", 5000)
                        ?.onSuccess { _uiState.value = _uiState.value.copy(latencyBaidu = it.delay) }
                        ?.onFailure { _uiState.value = _uiState.value.copy(latencyBaidu = -1) }
                }
                // Cloudflare/Google 通过用户选择的代理组测试
                val cfJob = launch {
                    repository?.getProxyDelay(proxyGroup, "http://www.cloudflare.com/cdn-cgi/trace", 5000)
                        ?.onSuccess { _uiState.value = _uiState.value.copy(latencyCloudflare = it.delay) }
                        ?.onFailure { _uiState.value = _uiState.value.copy(latencyCloudflare = -1) }
                }
                val googleJob = launch {
                    repository?.getProxyDelay(proxyGroup, "http://www.google.com/generate_204", 5000)
                        ?.onSuccess { _uiState.value = _uiState.value.copy(latencyGoogle = it.delay) }
                        ?.onFailure { _uiState.value = _uiState.value.copy(latencyGoogle = -1) }
                }
                baiduJob.join()
                cfJob.join()
                googleJob.join()
            } finally {
                _uiState.value = _uiState.value.copy(isTestingLatency = false)
            }
        }
    }

    /**
     * 仅 cancel 自身订阅的流；不再 close repository（owner 是 connectionManager）。
     * 当 repository StateFlow 切回 null 时由收集回调调用。
     */
    private fun disconnectStreams() {
        trafficJob?.cancel()
        memoryJob?.cancel()
        systemInfoJob?.cancel()
        runtimeConfigJob?.cancel()
        uptimeJob?.cancel()
        // mihomo 断开时清掉 live provider，订阅页立即回退到 DB 数据
        onLiveProviderInfo(null, null)
        mihomoPid = -1
    }

    override fun onCleared() {
        super.onCleared()
        disconnectStreams()
    }
}
