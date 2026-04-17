package top.yukonga.mishka.ui.screen.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.mishka.viewmodel.HomeUiState
import top.yukonga.mishka.viewmodel.HomeViewModel
import top.yukonga.mishka.viewmodel.MemorySnapshot
import top.yukonga.mishka.viewmodel.SpeedSnapshot
import top.yukonga.mishka.viewmodel.SystemInfoSnapshot
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
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
    onSwitchProxyGroup: (String) -> Unit = {},
) {
    val scrollBehavior = MiuixScrollBehavior()

    // 高频字段独立订阅，重组仅限到相应 Section
    val speed by (viewModel?.speedState?.collectAsState() ?: remember { mutableStateOf(SpeedSnapshot()) })
    val memory by (viewModel?.memoryState?.collectAsState() ?: remember { mutableStateOf(MemorySnapshot()) })
    val systemInfo by (viewModel?.systemInfoState?.collectAsState() ?: remember { mutableStateOf(SystemInfoSnapshot()) })
    val uptime by (viewModel?.uptimeState?.collectAsState() ?: remember { mutableStateOf("") })

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = "Mishka",
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = bottomPadding,
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
            quickEntriesSection(
                onNavigateLog = onNavigateLog,
                onNavigateProvider = onNavigateProvider,
                onNavigateConnection = onNavigateConnection,
                onNavigateDnsQuery = onNavigateDnsQuery,
            )
            latencySection(uiState, onTestLatency, onSwitchProxyGroup)
            networkInfoSection(speed = speed, systemInfo = systemInfo)
            bottomCardsSection(state = uiState, memory = memory, systemInfo = systemInfo)
        }
    }
}
