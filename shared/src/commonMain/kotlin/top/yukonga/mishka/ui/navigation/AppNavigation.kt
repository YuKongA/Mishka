package top.yukonga.mishka.ui.navigation

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.NavDisplayTransitionEffects
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import top.yukonga.mishka.platform.FilePicker
import top.yukonga.mishka.ui.navigation3.LocalNavigator
import top.yukonga.mishka.ui.navigation3.Navigator
import top.yukonga.mishka.ui.navigation3.Route
import top.yukonga.mishka.ui.screen.connection.ConnectionScreen
import top.yukonga.mishka.ui.screen.dns.DnsQueryScreen
import top.yukonga.mishka.ui.screen.home.HomeScreen
import top.yukonga.mishka.ui.screen.log.LogScreen
import top.yukonga.mishka.ui.screen.provider.ProviderScreen
import top.yukonga.mishka.ui.screen.proxy.ProxyScreen
import top.yukonga.mishka.ui.screen.settings.AboutScreen
import top.yukonga.mishka.ui.screen.settings.AppProxyScreen
import top.yukonga.mishka.ui.screen.settings.NetworkSettingsScreen
import top.yukonga.mishka.ui.screen.settings.SettingsScreen
import top.yukonga.mishka.ui.screen.subscription.SubscriptionAddScreen
import top.yukonga.mishka.ui.screen.subscription.SubscriptionAddUrlScreen
import top.yukonga.mishka.ui.screen.subscription.SubscriptionScreen
import top.yukonga.mishka.viewmodel.AppProxyViewModel
import top.yukonga.mishka.viewmodel.ConnectionViewModel
import top.yukonga.mishka.viewmodel.DnsQueryViewModel
import top.yukonga.mishka.viewmodel.HomeUiState
import top.yukonga.mishka.viewmodel.HomeViewModel
import top.yukonga.mishka.viewmodel.LogViewModel
import top.yukonga.mishka.viewmodel.ProviderViewModel
import top.yukonga.mishka.viewmodel.ProxyViewModel
import top.yukonga.mishka.viewmodel.SettingsViewModel
import top.yukonga.mishka.viewmodel.SubscriptionViewModel
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Sidebar
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import kotlin.math.abs

val LocalMainPagerState = staticCompositionLocalOf<MainPagerState> {
    error("LocalMainPagerState not provided")
}

@Composable
fun AppNavigation(
    colorMode: Int = 0,
    onColorModeChange: (Int) -> Unit = {},
    homeViewModel: HomeViewModel? = null,
    subscriptionViewModel: SubscriptionViewModel? = null,
    proxyViewModel: ProxyViewModel? = null,
    logViewModel: LogViewModel? = null,
    providerViewModel: ProviderViewModel? = null,
    connectionViewModel: ConnectionViewModel? = null,
    dnsQueryViewModel: DnsQueryViewModel? = null,
    settingsViewModel: SettingsViewModel? = null,
    appProxyViewModel: AppProxyViewModel? = null,
    filePicker: FilePicker? = null,
    storage: top.yukonga.mishka.platform.PlatformStorage? = null,
    bootStartManager: top.yukonga.mishka.platform.BootStartManager? = null,
    mihomoVersion: String = "",
) {
    val backStack = remember { mutableStateListOf<NavKey>(Route.Main) }
    val navigator = remember { Navigator(backStack) }
    val pagerState = rememberPagerState(pageCount = { 4 })
    val mainPagerState = rememberMainPagerState(pagerState)

    LaunchedEffect(mainPagerState.pagerState.currentPage) {
        mainPagerState.syncPage()
    }

    MainScreenBackHandler(mainPagerState, navigator)

    CompositionLocalProvider(
        LocalNavigator provides navigator,
        LocalMainPagerState provides mainPagerState,
    ) {
        val provider = entryProvider<NavKey> {
            entry<Route.Main> {
                MainPage(homeViewModel, proxyViewModel, subscriptionViewModel, navigator, mainPagerState, bootStartManager, colorMode, onColorModeChange, storage)
            }
            entry<Route.Subscription> {
                subscriptionViewModel?.let {
                    SubscriptionScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                        onNavigateAdd = { navigator.push(Route.SubscriptionAdd) },
                    )
                }
            }
            entry<Route.SubscriptionAdd> {
                SubscriptionAddScreen(
                    viewModel = subscriptionViewModel,
                    onBack = { navigator.pop() },
                    onPickFile = {
                        filePicker?.pickYamlFile { result ->
                            if (result != null && subscriptionViewModel != null) {
                                subscriptionViewModel.addFromFile(
                                    fileName = result.fileName,
                                    content = result.content,
                                    onComplete = {
                                        navigator.popUntil { key -> key is Route.Subscription }
                                    },
                                )
                            }
                        }
                    },
                    onNavigateUrl = { navigator.push(Route.SubscriptionAddUrl) },
                )
            }
            entry<Route.SubscriptionAddUrl> {
                subscriptionViewModel?.let {
                    SubscriptionAddUrlScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                        onSaved = { navigator.popUntil { key -> key is Route.Subscription } },
                    )
                }
            }
            entry<Route.Log> {
                logViewModel?.let {
                    LogScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.Provider> {
                providerViewModel?.let {
                    ProviderScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.Connection> {
                connectionViewModel?.let {
                    ConnectionScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.DnsQuery> {
                dnsQueryViewModel?.let {
                    DnsQueryScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.NetworkSettings> {
                settingsViewModel?.let {
                    NetworkSettingsScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.AppProxy> {
                appProxyViewModel?.let {
                    AppProxyScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.About> {
                AboutScreen(
                    onBack = { navigator.pop() },
                    mihomoVersion = mihomoVersion,
                )
            }
        }

        val entries = rememberDecoratedNavEntries(
            backStack = backStack,
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
            entryProvider = provider,
        )

        NavDisplay(
            entries = entries,
            onBack = { navigator.pop() },
            transitionEffects = NavDisplayTransitionEffects(
                enableCornerClip = true,
                dimAmount = 0.5f,
                blockInputDuringTransition = true,
            ),
        )
    }
}

@Composable
private fun MainPage(
    homeViewModel: HomeViewModel?,
    proxyViewModel: ProxyViewModel?,
    subscriptionViewModel: SubscriptionViewModel?,
    navigator: Navigator,
    mainPagerState: MainPagerState,
    bootStartManager: top.yukonga.mishka.platform.BootStartManager? = null,
    colorMode: Int = 0,
    onColorModeChange: (Int) -> Unit = {},
    storage: top.yukonga.mishka.platform.PlatformStorage? = null,
) {
    val homeUiState = homeViewModel?.uiState?.collectAsState()?.value ?: HomeUiState()
    val selectedPage = mainPagerState.selectedPage

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedPage == 0,
                    onClick = { mainPagerState.animateToPage(0) },
                    icon = MiuixIcons.Sidebar,
                    label = "主页",
                )
                NavigationBarItem(
                    selected = selectedPage == 1,
                    onClick = { mainPagerState.animateToPage(1) },
                    icon = MiuixIcons.Tune,
                    label = "代理",
                )
                NavigationBarItem(
                    selected = selectedPage == 2,
                    onClick = { mainPagerState.animateToPage(2) },
                    icon = MiuixIcons.UploadCloud,
                    label = "订阅",
                )
                NavigationBarItem(
                    selected = selectedPage == 3,
                    onClick = { mainPagerState.animateToPage(3) },
                    icon = MiuixIcons.Settings,
                    label = "设置",
                )
            }
        },
    ) { padding ->
        val bottomPadding = padding.calculateBottomPadding()

        HorizontalPager(
            state = mainPagerState.pagerState,
            verticalAlignment = Alignment.Top,
            userScrollEnabled = false,
            beyondViewportPageCount = 3,
        ) { page ->
            when (page) {
                0 -> HomeScreen(
                    bottomPadding = bottomPadding,
                    uiState = homeUiState,
                    onRestart = { homeViewModel?.restartProxy() },
                    onStop = { homeViewModel?.stopProxy() },
                    onReload = { homeViewModel?.reloadConfig() },
                    onTestLatency = { homeViewModel?.testLatency() },
                    onNavigateLog = { navigator.push(Route.Log) },
                    onNavigateProvider = { navigator.push(Route.Provider) },
                    onNavigateConnection = { navigator.push(Route.Connection) },
                    onNavigateDnsQuery = { navigator.push(Route.DnsQuery) },
                    onStartProxy = { homeViewModel?.startProxy() },
                    onSwitchMode = { homeViewModel?.switchMode(it) },
                    onSwitchTunStack = { homeViewModel?.switchTunStack(it) },
                    onSwitchProxyGroup = { homeViewModel?.switchProxyGroup(it) },
                )

                1 -> ProxyScreen(bottomPadding = bottomPadding, viewModel = proxyViewModel)
                2 -> subscriptionViewModel?.let {
                    SubscriptionScreen(
                        viewModel = it,
                        bottomPadding = bottomPadding,
                        onNavigateAdd = { navigator.push(Route.SubscriptionAdd) },
                    )
                }

                3 -> SettingsScreen(
                    bottomPadding = bottomPadding,
                    onNavigateNetworkSettings = { navigator.push(Route.NetworkSettings) },
                    onNavigateAppProxy = { navigator.push(Route.AppProxy) },
                    onNavigateAbout = { navigator.push(Route.About) },
                    bootStartManager = bootStartManager,
                    colorMode = colorMode,
                    onColorModeChange = onColorModeChange,
                    storage = storage,
                )
            }
        }
    }
}

// === MainPagerState（参考 miuix example）===

@Stable
class MainPagerState(
    val pagerState: PagerState,
    private val coroutineScope: CoroutineScope,
) {
    var selectedPage by mutableIntStateOf(pagerState.currentPage)
        private set

    var isNavigating by mutableStateOf(false)
        private set

    private var navJob: Job? = null

    fun animateToPage(targetIndex: Int) {
        if (targetIndex == selectedPage) return

        navJob?.cancel()
        selectedPage = targetIndex
        isNavigating = true

        navJob = coroutineScope.launch {
            val myJob = coroutineContext.job
            try {
                pagerState.scroll(MutatePriority.UserInput) {
                    val distance = abs(targetIndex - pagerState.currentPage).coerceAtLeast(2)
                    val duration = 100 * distance + 100
                    val layoutInfo = pagerState.layoutInfo
                    val pageSize = layoutInfo.pageSize + layoutInfo.pageSpacing
                    val currentDistanceInPages =
                        targetIndex - pagerState.currentPage - pagerState.currentPageOffsetFraction
                    val scrollPixels = currentDistanceInPages * pageSize

                    var previousValue = 0f
                    animate(
                        initialValue = 0f,
                        targetValue = scrollPixels,
                        animationSpec = tween(easing = EaseInOut, durationMillis = duration),
                    ) { currentValue, _ ->
                        previousValue += scrollBy(currentValue - previousValue)
                    }
                }

                if (pagerState.currentPage != targetIndex) {
                    pagerState.scrollToPage(targetIndex)
                }
            } finally {
                if (navJob == myJob) {
                    isNavigating = false
                    if (pagerState.currentPage != targetIndex) {
                        selectedPage = pagerState.currentPage
                    }
                }
            }
        }
    }

    fun syncPage() {
        if (!isNavigating && selectedPage != pagerState.currentPage) {
            selectedPage = pagerState.currentPage
        }
    }
}

@Composable
fun rememberMainPagerState(
    pagerState: PagerState,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
): MainPagerState = remember(pagerState, coroutineScope) {
    MainPagerState(pagerState, coroutineScope)
}

// === 返回键处理（参考 miuix example）===

@Composable
private fun MainScreenBackHandler(
    mainState: MainPagerState,
    navigator: Navigator,
) {
    val isPagerBackHandlerEnabled by remember {
        derivedStateOf {
            navigator.current() is Route.Main &&
                    navigator.backStackSize() == 1 &&
                    mainState.selectedPage != 0
        }
    }

    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)

    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = isPagerBackHandlerEnabled,
        onBackCompleted = {
            mainState.animateToPage(0)
        },
    )
}
