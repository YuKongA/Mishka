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
import top.yukonga.mishka.data.api.MihomoApiClient
import top.yukonga.mishka.data.api.MihomoWebSocket
import top.yukonga.mishka.data.model.MihomoConfig
import top.yukonga.mishka.data.model.SubscriptionInfo
import top.yukonga.mishka.data.model.TunOverride
import top.yukonga.mishka.data.repository.MihomoRepository
import top.yukonga.mishka.data.repository.OverrideJsonStore
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.PlatformSystemInfo
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.platform.ProxyState
import top.yukonga.mishka.util.FormatUtils
import kotlin.time.Clock

/** 低频状态：mihomo 运行状态、配置、代理组、延迟、错误等；改变频率与生命周期事件相当 */
@Immutable
data class HomeUiState(
    val isRunning: Boolean = false,
    val isStarting: Boolean = false,
    val isStopping: Boolean = false,
    val mode: String = "--",
    val tunStack: String = "",
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
    private val storage: PlatformStorage,
    private val overrideStore: OverrideJsonStore,
    private val getActiveSubscriptionId: () -> String? = { null },
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
    private var startTime: Long = 0
    private var uptimeJob: Job? = null
    private var mihomoPid: Int = -1
    private val systemInfo = PlatformSystemInfo()

    init {
        viewModelScope.launch {
            serviceController.status.collect { status ->
                when (status.state) {
                    ProxyState.Starting -> {
                        _uiState.value = _uiState.value.copy(isStarting = true, isStopping = false)
                    }
                    ProxyState.Running -> {
                        _uiState.value = _uiState.value.copy(isStarting = false, isRunning = true)
                        val client = MihomoApiClient(baseUrl = "http://${status.externalController}", secret = status.secret)
                        val ws = MihomoWebSocket(client)
                        repository = MihomoRepository(client, ws)
                        startTime = if (status.startTime > 0) status.startTime else Clock.System.now().toEpochMilliseconds()
                        mihomoPid = status.mihomoPid
                        connectToMihomo()
                    }
                    ProxyState.Stopping -> {
                        disconnect()
                        _uiState.value = _uiState.value.copy(isRunning = false, isStopping = true)
                    }
                    ProxyState.Stopped -> {
                        disconnect()
                        _uiState.value = HomeUiState()
                        resetHotStates()
                    }
                    ProxyState.Error -> {
                        disconnect()
                        _uiState.value = HomeUiState(errorMessage = status.errorMessage)
                        resetHotStates()
                    }
                }
            }
        }
    }

    private fun connectToMihomo() {
        startTrafficCollection()
        startMemoryCollection()
        startSystemInfoCollection()
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
        repository?.getProviders()?.onSuccess { providers ->
            val sub = providers.providers.values.firstOrNull()?.subscriptionInfo
            _uiState.value = _uiState.value.copy(subscription = sub)
        }
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
                delay(1000)
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
                delay(2000)
            }
        }
    }

    private fun resetHotStates() {
        _speedState.value = SpeedSnapshot()
        _memoryState.value = MemorySnapshot()
        _systemInfoState.value = SystemInfoSnapshot()
        _uptimeState.value = -1L
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

    fun switchMode(mode: String) {
        val current = overrideStore.load()
        overrideStore.save(current.copy(mode = mode))
        _uiState.value = _uiState.value.copy(mode = mode)
        serviceController.restart(getActiveSubscriptionId())
    }

    fun switchTunStack(stack: String) {
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

    private fun disconnect() {
        trafficJob?.cancel()
        memoryJob?.cancel()
        systemInfoJob?.cancel()
        uptimeJob?.cancel()
        repository?.close()
        repository = null
        mihomoPid = -1
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
