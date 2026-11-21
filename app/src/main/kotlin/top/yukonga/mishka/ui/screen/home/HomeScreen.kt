package top.yukonga.mishka.ui.screen.home

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.toPersistentList
import top.yukonga.mishka.R
import top.yukonga.mishka.domain.model.SubscriptionInfo
import top.yukonga.mishka.platform.NetworkAddressType
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.LocalTopBarStartInset
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.overScrollVertical
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.util.formatUptime
import top.yukonga.mishka.viewmodel.HomeUiState
import top.yukonga.mishka.viewmodel.HomeViewModel
import top.yukonga.mishka.viewmodel.MemorySnapshot
import top.yukonga.mishka.viewmodel.SpeedSnapshot
import top.yukonga.mishka.viewmodel.SystemInfoSnapshot
import top.yukonga.mishka.viewmodel.TrafficHistory
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    uiState: HomeUiState = HomeUiState(),
    viewModel: HomeViewModel? = null,
    onStop: () -> Unit = {},
    onRestart: () -> Unit = {},
    onTestLatency: () -> Unit = {},
    onNavigateLog: () -> Unit = {},
    onNavigateProvider: () -> Unit = {},
    onNavigateConnection: () -> Unit = {},
    onNavigateDnsQuery: () -> Unit = {},
    onStartProxy: () -> Unit = {},
    onSwitchMode: (String) -> Unit = {},
    onSwitchTunStack: (String) -> Unit = {},
) {

    // 高频字段拆成独立 Flow：1Hz 推送仍会重组 HomeScreen 本体与 LazyColumn 的 content lambda，
    // 但只有拿到新值的那个 Section 参数变了，其余全部 skip，实际重绘收在对应 Section 内
    val speed by (viewModel?.speedState?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(SpeedSnapshot()) })
    val memory by (viewModel?.memoryState?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(MemorySnapshot()) })
    val systemInfo by (viewModel?.systemInfoState?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(SystemInfoSnapshot()) })
    val uptimeSeconds by (viewModel?.uptimeState?.collectAsStateWithLifecycle() ?: remember { mutableLongStateOf(-1L) })
    val uptime = formatUptime(uptimeSeconds)
    val topConnectionRates by (
            viewModel?.topConnectionRates?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(null) }
            )

    HomeScreenContent(
        modifier = modifier,
        bottomPadding = bottomPadding,
        uiState = uiState,
        speed = speed,
        memory = memory,
        systemInfo = systemInfo,
        uptime = uptime,
        topConnectionRates = topConnectionRates,
        onStartConnectionRateTracking = { viewModel?.startConnectionRateTracking() },
        onStopConnectionRateTracking = { viewModel?.stopConnectionRateTracking() },
        onRefreshProviderTraffic = { viewModel?.refreshProviderTraffic() },
        onUpdateAllProviders = { viewModel?.updateAllProviders() },
        onStop = onStop,
        onRestart = onRestart,
        onTestLatency = onTestLatency,
        onNavigateLog = onNavigateLog,
        onNavigateProvider = onNavigateProvider,
        onNavigateConnection = onNavigateConnection,
        onNavigateDnsQuery = onNavigateDnsQuery,
        onStartProxy = onStartProxy,
        onSwitchMode = onSwitchMode,
        onSwitchTunStack = onSwitchTunStack,
    )
}

@Composable
private fun HomeScreenContent(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    uiState: HomeUiState = HomeUiState(),
    speed: SpeedSnapshot = SpeedSnapshot(),
    memory: MemorySnapshot = MemorySnapshot(),
    systemInfo: SystemInfoSnapshot = SystemInfoSnapshot(),
    uptime: String = "",
    topConnectionRates: kotlinx.collections.immutable.ImmutableList<top.yukonga.mishka.viewmodel.ConnectionRate>? = null,
    onStartConnectionRateTracking: () -> Unit = {},
    onStopConnectionRateTracking: () -> Unit = {},
    onRefreshProviderTraffic: () -> Unit = {},
    onUpdateAllProviders: () -> Unit = {},
    onStop: () -> Unit = {},
    onRestart: () -> Unit = {},
    onTestLatency: () -> Unit = {},
    onNavigateLog: () -> Unit = {},
    onNavigateProvider: () -> Unit = {},
    onNavigateConnection: () -> Unit = {},
    onNavigateDnsQuery: () -> Unit = {},
    onStartProxy: () -> Unit = {},
    onSwitchMode: (String) -> Unit = {},
    onSwitchTunStack: (String) -> Unit = {},
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    var showSubscriptionTraffic by rememberSaveable { mutableStateOf(false) }
    var showSpeedDetail by rememberSaveable { mutableStateOf(false) }

    // WS 订阅只在详情打开期间存续，理由见 startConnectionRateTracking
    if (showSpeedDetail) {
        DisposableEffect(Unit) {
            onStartConnectionRateTracking()
            onDispose { onStopConnectionRateTracking() }
        }
    }
    // 代理停止后不会再有快照可差分，速率被重置回「未就绪」，留着详情只会无限转圈
    LaunchedEffect(uiState.isRunning) {
        if (!uiState.isRunning) showSpeedDetail = false
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.app_name),
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { innerPadding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isTablet = (maxWidth - LocalTopBarStartInset.current()) >= 960.dp
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                    .horizontalCutoutPadding()
                    .scrollEndHaptic()
                    .overScrollVertical(scrollBehavior)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = bottomPadding,
                ),
            ) {
                val hasSubscriptionQuota = (uiState.subscription?.Total ?: 0L) > 0L
                if (isTablet) {
                    item(key = "tablet_dashboard") {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            CompositionLocalProvider(
                                LocalHomeCardHorizontalPadding provides PaddingValues(start = 12.dp, end = 6.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    StatusContent(
                                        state = uiState,
                                        uptime = uptime,
                                        onSwitchMode = onSwitchMode,
                                        onSwitchTunStack = onSwitchTunStack,
                                        onRestart = onRestart,
                                        onStop = onStop,
                                        onStart = onStartProxy,
                                    )
                                    LatencySectionContent(uiState, onTestLatency)
                                    if (hasSubscriptionQuota) {
                                        QuickEntriesContent(
                                            isRunning = uiState.isRunning,
                                            onNavigateLog = onNavigateLog,
                                            onNavigateProvider = onNavigateProvider,
                                            onNavigateConnection = onNavigateConnection,
                                            onNavigateDnsQuery = onNavigateDnsQuery,
                                        )
                                    }
                                }
                            }
                            CompositionLocalProvider(
                                LocalHomeCardHorizontalPadding provides PaddingValues(start = 6.dp, end = 12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    OverviewCardsContent(
                                        speed = speed,
                                        state = uiState,
                                        onSpeedClick = { showSpeedDetail = true },
                                        onSubscriptionClick = {
                                            showSubscriptionTraffic = true
                                            onRefreshProviderTraffic()
                                        },
                                    )
                                    SystemCardsContent(
                                        memory = memory,
                                        systemInfo = systemInfo,
                                    )
                                    if (!hasSubscriptionQuota) {
                                        QuickEntriesContent(
                                            isRunning = uiState.isRunning,
                                            onNavigateLog = onNavigateLog,
                                            onNavigateProvider = onNavigateProvider,
                                            onNavigateConnection = onNavigateConnection,
                                            onNavigateDnsQuery = onNavigateDnsQuery,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    statusSection(
                        state = uiState,
                        uptime = uptime,
                        onSwitchMode = onSwitchMode,
                        onSwitchTunStack = onSwitchTunStack,
                        onRestart = onRestart,
                        onStop = onStop,
                        onStart = onStartProxy,
                    )
                    latencySection(uiState, onTestLatency)
                    overviewCardsSection(
                        speed = speed,
                        state = uiState,
                        onSpeedClick = { showSpeedDetail = true },
                        onSubscriptionClick = {
                            showSubscriptionTraffic = true
                            onRefreshProviderTraffic()
                        },
                    )
                    systemCardsSection(
                        memory = memory,
                        systemInfo = systemInfo,
                    )
                    quickEntriesSection(
                        isRunning = uiState.isRunning,
                        onNavigateLog = onNavigateLog,
                        onNavigateProvider = onNavigateProvider,
                        onNavigateConnection = onNavigateConnection,
                        onNavigateDnsQuery = onNavigateDnsQuery,
                    )
                }
            }
        }
    }

    SpeedDetailSheet(
        show = showSpeedDetail,
        rates = topConnectionRates,
        onDismiss = { showSpeedDetail = false },
    )

    SubscriptionTrafficDialog(
        show = showSubscriptionTraffic,
        providers = uiState.providerTraffic,
        isLoading = uiState.isProviderTrafficLoading,
        loadFailed = uiState.providerTrafficLoadFailed,
        onUpdateAll = onUpdateAllProviders,
        onDismiss = { showSubscriptionTraffic = false },
    )
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenStoppedPreview() {
    MiuixTheme {
        HomeScreenContent(
            uiState = HomeUiState(
                isRunning = false,
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenRunningPreview() {
    MiuixTheme {
        HomeScreenContent(
            uiState = HomeUiState(
                isRunning = true,
                mode = "rule",
                tunStack = "mips",
                subscription = SubscriptionInfo(
                    Upload = 12L * 1024 * 1024 * 1024,
                    Download = 68L * 1024 * 1024 * 1024,
                    Total = 200L * 1024 * 1024 * 1024,
                    Expire = 1798761600L,
                ),
                latencyXiaomi = 42,
                latencyCloudflare = 320,
                latencyGoogle = 680,
            ),
            speed = SpeedSnapshot(
                uploadSpeed = "128 KB/s",
                downloadSpeed = "2.4 MB/s",
                history = TrafficHistory(
                    up = List(60) { i -> ((i % 7 + 1) * 40_000L) }.toPersistentList(),
                    down = List(60) { i -> ((i % 5 + 2) * 380_000L) }.toPersistentList(),
                    seq = 60,
                ),
            ),
            memory = MemorySnapshot(
                ramUsage = "38.4 MB",
                ramTotal = "284 MB",
            ),
            systemInfo = SystemInfoSnapshot(
                localIp = "172.19.0.1",
                interfaceName = "tun0",
                addressType = NetworkAddressType.Tun,
                cpuUsage = "1.2%",
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 840, heightDp = 420)
@Composable
private fun HomeScreenRunningWidePreview() {
    MiuixTheme {
        HomeScreenContent(
            uiState = HomeUiState(
                isRunning = true,
                mode = "rule",
                tunStack = "mips",
                subscription = SubscriptionInfo(
                    Upload = 12L * 1024 * 1024 * 1024,
                    Download = 68L * 1024 * 1024 * 1024,
                    Total = 200L * 1024 * 1024 * 1024,
                    Expire = 1798761600L,
                ),
                latencyXiaomi = 42,
                latencyCloudflare = 320,
                latencyGoogle = 680,
            ),
            speed = SpeedSnapshot(
                uploadSpeed = "128 KB/s",
                downloadSpeed = "2.4 MB/s",
                history = TrafficHistory(
                    up = List(60) { i -> ((i % 7 + 1) * 40_000L) }.toPersistentList(),
                    down = List(60) { i -> ((i % 5 + 2) * 380_000L) }.toPersistentList(),
                    seq = 60,
                ),
            ),
            memory = MemorySnapshot(
                ramUsage = "38.4 MB",
                ramTotal = "284 MB",
            ),
            systemInfo = SystemInfoSnapshot(
                localIp = "172.19.0.1",
                interfaceName = "tun0",
                addressType = NetworkAddressType.Tun,
                cpuUsage = "1.2%",
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
private fun HomeScreenRunningTabletPreview() {
    MiuixTheme {
        HomeScreenContent(
            uiState = HomeUiState(
                isRunning = true,
                mode = "rule",
                tunStack = "mips",
                subscription = SubscriptionInfo(
                    Upload = 12L * 1024 * 1024 * 1024,
                    Download = 68L * 1024 * 1024 * 1024,
                    Total = 200L * 1024 * 1024 * 1024,
                    Expire = 1798761600L,
                ),
                latencyXiaomi = 42,
                latencyCloudflare = 320,
                latencyGoogle = 680,
            ),
            speed = SpeedSnapshot(
                uploadSpeed = "128 KB/s",
                downloadSpeed = "2.4 MB/s",
                history = TrafficHistory(
                    up = List(60) { i -> ((i % 7 + 1) * 40_000L) }.toPersistentList(),
                    down = List(60) { i -> ((i % 5 + 2) * 380_000L) }.toPersistentList(),
                    seq = 60,
                ),
            ),
            memory = MemorySnapshot(
                ramUsage = "38.4 MB",
                ramTotal = "284 MB",
            ),
            systemInfo = SystemInfoSnapshot(
                localIp = "172.19.0.1",
                interfaceName = "tun0",
                addressType = NetworkAddressType.Tun,
                cpuUsage = "1.2%",
            ),
        )
    }
}
