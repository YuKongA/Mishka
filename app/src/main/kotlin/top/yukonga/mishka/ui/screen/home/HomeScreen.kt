package top.yukonga.mishka.ui.screen.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.mishka.R
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.util.WideContentBox
import top.yukonga.mishka.util.formatUptime
import top.yukonga.mishka.viewmodel.HomeUiState
import top.yukonga.mishka.viewmodel.HomeViewModel
import top.yukonga.mishka.viewmodel.MemorySnapshot
import top.yukonga.mishka.viewmodel.SpeedSnapshot
import top.yukonga.mishka.viewmodel.SystemInfoSnapshot
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    uiState: HomeUiState = HomeUiState(),
    viewModel: HomeViewModel? = null,
    onRestart: () -> Unit = {},
    onStop: () -> Unit = {},
    onReload: () -> Unit = {},
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

    // 高频字段拆成独立 Flow：1Hz 推送仍会重组 HomeScreen 本体与 LazyColumn 的 content lambda，
    // 但只有拿到新值的那个 Section 参数变了，其余全部 skip，实际重绘收在对应 Section 内
    val speed by (viewModel?.speedState?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(SpeedSnapshot()) })
    val memory by (viewModel?.memoryState?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(MemorySnapshot()) })
    val systemInfo by (viewModel?.systemInfoState?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(SystemInfoSnapshot()) })
    val uptimeSeconds by (viewModel?.uptimeState?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(-1L) })
    val uptime = formatUptime(uptimeSeconds)

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    var showSubscriptionTraffic by rememberSaveable { mutableStateOf(false) }
    var showSpeedDetail by rememberSaveable { mutableStateOf(false) }
    val topConnectionRates by (
            viewModel?.topConnectionRates?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(null) }
            )

    // WS 订阅只在详情打开期间存续，理由见 startConnectionRateTracking
    if (showSpeedDetail) {
        DisposableEffect(viewModel) {
            viewModel?.startConnectionRateTracking()
            onDispose { viewModel?.stopConnectionRateTracking() }
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
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { innerPadding ->
        WideContentBox { sidePadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = bottomPadding,
                    start = sidePadding,
                    end = sidePadding,
                ),
            ) {
                statusSection(
                    state = uiState,
                    uptime = uptime,
                    onSwitchMode = onSwitchMode,
                    onSwitchTunStack = onSwitchTunStack,
                )
                actionButtonsSection(
                    onRestart = onRestart,
                    onStop = onStop,
                    onReload = onReload,
                    onStart = onStartProxy,
                    isRunning = uiState.isRunning,
                    isStarting = uiState.isStarting,
                    isStopping = uiState.isStopping,
                )
                overviewCardsSection(
                    speed = speed,
                    state = uiState,
                    onSpeedClick = { showSpeedDetail = true },
                    onSubscriptionClick = {
                        showSubscriptionTraffic = true
                        viewModel?.refreshProviderTraffic()
                    },
                )
                systemCardsSection(
                    memory = memory,
                    systemInfo = systemInfo,
                )
                latencySection(uiState, onTestLatency)
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
        onUpdateAll = { viewModel?.updateAllProviders() },
        onDismiss = { showSubscriptionTraffic = false },
    )
}
