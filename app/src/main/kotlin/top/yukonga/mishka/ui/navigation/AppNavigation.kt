package top.yukonga.mishka.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import top.yukonga.mishka.DeepLinkImportRequest
import top.yukonga.mishka.R
import top.yukonga.mishka.platform.BootStartManager
import top.yukonga.mishka.platform.FilePicker
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.WifiPolicyController
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.liquid.IosLiquidGlassNavigationBar
import top.yukonga.mishka.ui.screen.connection.ConnectionScreen
import top.yukonga.mishka.ui.screen.dns.DnsQueryScreen
import top.yukonga.mishka.ui.screen.home.HomeScreen
import top.yukonga.mishka.ui.screen.log.LogScreen
import top.yukonga.mishka.ui.screen.provider.ProviderScreen
import top.yukonga.mishka.ui.screen.proxy.ProxyScreen
import top.yukonga.mishka.ui.screen.settings.AboutScreen
import top.yukonga.mishka.ui.screen.settings.AppProxyScreen
import top.yukonga.mishka.ui.screen.settings.BackupRestoreScreen
import top.yukonga.mishka.ui.screen.settings.ExternalControlScreen
import top.yukonga.mishka.ui.screen.settings.FileManagerEditorScreen
import top.yukonga.mishka.ui.screen.settings.FileManagerScreen
import top.yukonga.mishka.ui.screen.settings.MetaSettingsScreen
import top.yukonga.mishka.ui.screen.settings.NetworkSettingsScreen
import top.yukonga.mishka.ui.screen.settings.RootSettingsScreen
import top.yukonga.mishka.ui.screen.settings.SettingsScreen
import top.yukonga.mishka.ui.screen.settings.ThemeSettingsScreen
import top.yukonga.mishka.ui.screen.settings.VpnSettingsScreen
import top.yukonga.mishka.ui.screen.settings.WifiPolicyScreen
import top.yukonga.mishka.ui.screen.subscription.SubscriptionAddScreen
import top.yukonga.mishka.ui.screen.subscription.SubscriptionAddUrlScreen
import top.yukonga.mishka.ui.screen.subscription.SubscriptionEditScreen
import top.yukonga.mishka.ui.screen.subscription.SubscriptionScreen
import top.yukonga.mishka.ui.theme.BottomBarMode
import top.yukonga.mishka.ui.theme.FloatingBottomBarStyle
import top.yukonga.mishka.ui.theme.LocalAppDarkMode
import top.yukonga.mishka.ui.theme.ThemeConfig
import top.yukonga.mishka.ui.theme.TopBarBlurStyle
import top.yukonga.mishka.ui.util.rememberIsWideScreen
import top.yukonga.mishka.viewmodel.AppProxyViewModel
import top.yukonga.mishka.viewmodel.BackupViewModel
import top.yukonga.mishka.viewmodel.ConnectionViewModel
import top.yukonga.mishka.viewmodel.DnsQueryViewModel
import top.yukonga.mishka.viewmodel.ExternalControlViewModel
import top.yukonga.mishka.viewmodel.HomeUiState
import top.yukonga.mishka.viewmodel.HomeViewModel
import top.yukonga.mishka.viewmodel.LogViewModel
import top.yukonga.mishka.viewmodel.MetaSettingsViewModel
import top.yukonga.mishka.viewmodel.NetworkSettingsViewModel
import top.yukonga.mishka.viewmodel.ProviderViewModel
import top.yukonga.mishka.viewmodel.ProxyViewModel
import top.yukonga.mishka.viewmodel.SubscriptionViewModel
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.NavigationRailValue
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

// Route 是 @Serializable sealed 层级，直接多态编码整个栈；新增路由自动获得持久化能力，无需手工注册
private val NavBackStackSaver = Saver<SnapshotStateList<NavKey>, List<String>>(
    save = { backStack ->
        backStack.mapNotNull { key ->
            (key as? Route)?.let { Json.encodeToString<Route>(it) }
        }.ifEmpty {
            listOf(Json.encodeToString<Route>(Route.Main))
        }
    },
    restore = { savedRoutes ->
        val restoredRoutes = savedRoutes.mapNotNull { value ->
            runCatching { Json.decodeFromString<Route>(value) }.getOrNull()
        }
        mutableStateListOf<NavKey>().apply {
            if (restoredRoutes.isEmpty()) {
                add(Route.Main)
            } else {
                if (restoredRoutes.first() !is Route.Main) add(Route.Main)
                addAll(restoredRoutes)
            }
        }
    },
)

val LocalMainPagerState = staticCompositionLocalOf<MainPagerState> {
    error("LocalMainPagerState not provided")
}

@Composable
fun AppNavigation(
    themeConfig: ThemeConfig = ThemeConfig(),
    onThemeConfigChange: (ThemeConfig) -> Unit = {},
    homeViewModel: HomeViewModel? = null,
    subscriptionViewModel: SubscriptionViewModel? = null,
    proxyViewModel: ProxyViewModel? = null,
    logViewModel: LogViewModel? = null,
    providerViewModel: ProviderViewModel? = null,
    connectionViewModel: ConnectionViewModel? = null,
    dnsQueryViewModel: DnsQueryViewModel? = null,
    networkSettingsViewModel: NetworkSettingsViewModel? = null,
    metaSettingsViewModel: MetaSettingsViewModel? = null,
    externalControlViewModel: ExternalControlViewModel? = null,
    appProxyViewModel: AppProxyViewModel? = null,
    filePicker: FilePicker? = null,
    storage: PlatformStorage? = null,
    bootStartManager: BootStartManager? = null,
    mihomoVersion: String = "",
    onScanQR: ((callback: (String?) -> Unit) -> Unit)? = null,
    wifiPolicyController: WifiPolicyController? = null,
    onRequestWifiPermission: (((Boolean) -> Unit) -> Unit)? = null,
    onPredictiveBackChange: ((Boolean) -> Unit)? = null,
    onHideTaskCardChange: ((Boolean) -> Unit)? = null,
    hasRootPermission: Boolean = false,
    deepLinkImport: DeepLinkImportRequest? = null,
    onDeepLinkImportConsumed: () -> Unit = {},
    backupViewModel: BackupViewModel? = null,
    onRestartApp: () -> Unit = {},
) {
    val backStack = rememberSaveable(saver = NavBackStackSaver) { mutableStateListOf(Route.Main) }
    val navigator = remember { Navigator(backStack) }
    val pagerState = rememberPagerState(pageCount = { 4 })
    val useNavigationRail = rememberIsWideScreen()
    val mainPagerState = rememberMainPagerState(
        pagerState = pagerState,
        animatePageChanges = !useNavigationRail,
    )

    // 深链导入：回 Main 并把 pager 切到订阅 Tab 后直接压预填添加页（订阅管理是 pager Tab，无二级路由）
    LaunchedEffect(deepLinkImport) {
        if (deepLinkImport != null) {
            navigator.popUntil { key -> key is Route.Main }
            mainPagerState.animateToPage(2)
            navigator.push(
                Route.SubscriptionAddUrl(
                    initialUrl = deepLinkImport.url,
                    initialName = deepLinkImport.name,
                    initialIntervalMinutes = deepLinkImport.intervalMinutes,
                )
            )
            onDeepLinkImportConsumed()
        }
    }

    LaunchedEffect(mainPagerState.pagerState.currentPage) {
        mainPagerState.syncPage()
        // 切到代理 Tab 时刷新 mihomo 缓存的 history.delay；URL-Test 组的周期测试结果靠这里同步到 UI
        if (mainPagerState.pagerState.currentPage == 1) proxyViewModel?.loadProxies()
    }

    MainScreenBackHandler(mainPagerState, navigator)

    // 横移返回默认启用；设置页开关实时生效（storage 只持久化，本状态驱动 entry 的 swipeDismiss）
    var swipeDismissEnabled by remember {
        mutableStateOf(storage?.getString(StorageKeys.SWIPE_DISMISS, "true") == "true")
    }

    // 横移返回方向是物理方向（不随布局方向镜像），RTL 下要反过来
    val swipeBackDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        NavSwipeDirection.RightToLeft
    } else {
        NavSwipeDirection.LeftToRight
    }
    val swipeDismiss = if (swipeDismissEnabled) swipeBackDirection else null

    CompositionLocalProvider(
        LocalNavigator provides navigator,
        LocalMainPagerState provides mainPagerState,
    ) {
        NavDisplay(
            backStack = backStack,
            onBack = { navigator.pop() },
            effects = NavDisplayEffects(
                cornerClipRadius = rememberNavSystemCornerRadius(),
            ),
        ) {
            entry<Route.Main>(swipeDismiss = swipeDismiss) {
                MainPage(
                    homeViewModel,
                    proxyViewModel,
                    subscriptionViewModel,
                    navigator,
                    mainPagerState,
                    bootStartManager,
                    themeConfig,
                    storage,
                    onHideTaskCardChange,
                    hasRootPermission,
                    useNavigationRail,
                )
            }
            entry<Route.SubscriptionAdd>(swipeDismiss = swipeDismiss) {
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
                                        navigator.popUntil { key -> key is Route.Main }
                                    },
                                )
                            }
                        }
                    },
                    onNavigateUrl = { navigator.push(Route.SubscriptionAddUrl()) },
                    onScanQR = if (onScanQR != null) {
                        {
                            onScanQR { url ->
                                if (url != null) {
                                    navigator.push(Route.SubscriptionAddUrl(initialUrl = url))
                                }
                            }
                        }
                    } else null,
                )
            }
            entry<Route.SubscriptionAddUrl>(swipeDismiss = swipeDismiss) { route ->
                subscriptionViewModel?.let {
                    SubscriptionAddUrlScreen(
                        viewModel = it,
                        initialUrl = route.initialUrl,
                        initialName = route.initialName,
                        initialIntervalMinutes = route.initialIntervalMinutes,
                        onBack = { navigator.pop() },
                        onSaved = { navigator.popUntil { key -> key is Route.Main } },
                    )
                }
            }
            entry<Route.SubscriptionEdit>(swipeDismiss = swipeDismiss) { route ->
                subscriptionViewModel?.let {
                    SubscriptionEditScreen(
                        uuid = route.uuid,
                        viewModel = it,
                        onBack = { navigator.pop() },
                        onSaved = { navigator.pop() },
                    )
                }
            }
            entry<Route.Log>(swipeDismiss = swipeDismiss) {
                logViewModel?.let {
                    LogScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.Provider>(swipeDismiss = swipeDismiss) {
                providerViewModel?.let {
                    ProviderScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.Connection>(swipeDismiss = swipeDismiss) {
                connectionViewModel?.let {
                    ConnectionScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.DnsQuery>(swipeDismiss = swipeDismiss) {
                dnsQueryViewModel?.let {
                    DnsQueryScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.VpnSettings>(swipeDismiss = swipeDismiss) {
                storage?.let {
                    VpnSettingsScreen(
                        storage = it,
                        isSystemProxySupported = true,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.RootSettings>(swipeDismiss = swipeDismiss) {
                storage?.let {
                    val homeState = homeViewModel?.uiState?.collectAsStateWithLifecycle()?.value
                    RootSettingsScreen(
                        storage = it,
                        isProxyRunning = homeState?.isRunning == true || homeState?.isStarting == true,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.NetworkSettings>(swipeDismiss = swipeDismiss) {
                networkSettingsViewModel?.let {
                    NetworkSettingsScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.MetaSettings>(swipeDismiss = swipeDismiss) {
                metaSettingsViewModel?.let {
                    MetaSettingsScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.AppProxy>(swipeDismiss = swipeDismiss) {
                appProxyViewModel?.let {
                    AppProxyScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.WifiPolicy>(swipeDismiss = swipeDismiss) {
                storage?.let {
                    WifiPolicyScreen(
                        storage = it,
                        controller = wifiPolicyController,
                        onRequestPermission = onRequestWifiPermission,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.ThemeSettings>(swipeDismiss = swipeDismiss) {
                storage?.let {
                    ThemeSettingsScreen(
                        storage = it,
                        themeConfig = themeConfig,
                        onThemeConfigChange = onThemeConfigChange,
                        onPredictiveBackChange = onPredictiveBackChange,
                        swipeDismissEnabled = swipeDismissEnabled,
                        onSwipeDismissChange = { enabled ->
                            swipeDismissEnabled = enabled
                            it.putString(StorageKeys.SWIPE_DISMISS, enabled.toString())
                        },
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.ExternalControl>(swipeDismiss = swipeDismiss) {
                externalControlViewModel?.let {
                    ExternalControlScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.BackupRestore>(swipeDismiss = swipeDismiss) {
                if (backupViewModel != null && storage != null) {
                    BackupRestoreScreen(
                        viewModel = backupViewModel,
                        storage = storage,
                        filePicker = filePicker,
                        onRestartApp = onRestartApp,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.FileManager>(swipeDismiss = swipeDismiss) {
                FileManagerScreen(
                    subscriptionViewModel = subscriptionViewModel,
                    onBack = { navigator.pop() },
                    onOpenFile = { uuid, relPath ->
                        navigator.push(Route.FileManagerEditor(uuid, relPath))
                    },
                )
            }
            entry<Route.FileManagerEditor>(swipeDismiss = swipeDismiss) { route ->
                FileManagerEditorScreen(
                    uuid = route.uuid,
                    relativePath = route.relativePath,
                    subscriptionViewModel = subscriptionViewModel,
                    onBack = { navigator.pop() },
                )
            }
            entry<Route.About>(swipeDismiss = swipeDismiss) {
                val uriHandler = LocalUriHandler.current
                AboutScreen(
                    onBack = { navigator.pop() },
                    mihomoVersion = mihomoVersion,
                    onOpenUrl = { url -> uriHandler.openUri(url) },
                )
            }
        }
    }
}

@Composable
private fun MainPage(
    homeViewModel: HomeViewModel?,
    proxyViewModel: ProxyViewModel?,
    subscriptionViewModel: SubscriptionViewModel?,
    navigator: Navigator,
    mainPagerState: MainPagerState,
    bootStartManager: BootStartManager? = null,
    themeConfig: ThemeConfig = ThemeConfig(),
    storage: PlatformStorage? = null,
    onHideTaskCardChange: ((Boolean) -> Unit)? = null,
    hasRootPermission: Boolean = false,
    useNavigationRail: Boolean = false,
) {
    val homeUiState = homeViewModel?.uiState?.collectAsStateWithLifecycle()?.value ?: HomeUiState()
    val selectedPage = mainPagerState.selectedPage

    // 页面主体：手机与宽屏两套外壳共用，仅传入不同的容器 modifier 与底部留白
    val pagerContent: @Composable (Modifier, Dp) -> Unit = { pagerModifier, bottomPadding ->
        HorizontalPager(
            modifier = pagerModifier,
            state = mainPagerState.pagerState,
            verticalAlignment = Alignment.Top,
            overscrollEffect = null,
        ) { page ->
            when (page) {
                0 -> HomeScreen(
                    bottomPadding = bottomPadding,
                    uiState = homeUiState,
                    viewModel = homeViewModel,
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
                )

                1 -> ProxyScreen(bottomPadding = bottomPadding, viewModel = proxyViewModel)
                2 -> subscriptionViewModel?.let {
                    SubscriptionScreen(
                        viewModel = it,
                        bottomPadding = bottomPadding,
                        onNavigateAdd = { navigator.push(Route.SubscriptionAdd) },
                        onNavigateEdit = { uuid -> navigator.push(Route.SubscriptionEdit(uuid)) },
                        onActiveChanged = { homeViewModel?.onActiveSubscriptionChanged() },
                    )
                }

                3 -> SettingsScreen(
                    bottomPadding = bottomPadding,
                    onNavigateVpnSettings = { navigator.push(Route.VpnSettings) },
                    onNavigateRootSettings = { navigator.push(Route.RootSettings) },
                    onNavigateNetworkSettings = { navigator.push(Route.NetworkSettings) },
                    onNavigateMetaSettings = { navigator.push(Route.MetaSettings) },
                    onNavigateExternalControl = { navigator.push(Route.ExternalControl) },
                    onNavigateAppProxy = { navigator.push(Route.AppProxy) },
                    onNavigateWifiPolicy = { navigator.push(Route.WifiPolicy) },
                    onNavigateThemeSettings = { navigator.push(Route.ThemeSettings) },
                    onNavigateFileManager = { navigator.push(Route.FileManager) },
                    onNavigateBackup = { navigator.push(Route.BackupRestore) },
                    onNavigateAbout = { navigator.push(Route.About) },
                    bootStartManager = bootStartManager,
                    storage = storage,
                    onHideTaskCardChange = onHideTaskCardChange,
                    hasRootPermission = hasRootPermission,
                    isProxyRunning = homeUiState.isRunning || homeUiState.isStarting,
                )
            }
        }
    }

    if (useNavigationRail) {
        // 宽屏：侧边 NavigationRail 取代底部 NavigationBar，纵向空间全部让给内容
        val railState = rememberNavigationRailState(
            initialValue = if (storage?.getString(StorageKeys.NAV_RAIL_EXPANDED, "false") == "true") {
                NavigationRailValue.Expanded
            } else {
                NavigationRailValue.Collapsed
            },
        )
        LaunchedEffect(railState.currentValue, storage) {
            storage?.putString(StorageKeys.NAV_RAIL_EXPANDED, railState.isExpanded.toString())
        }
        Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
            Row(Modifier.fillMaxSize()) {
                NavigationRail(state = railState) {
                    NavigationRailItem(
                        selected = selectedPage == 0,
                        onClick = { mainPagerState.animateToPage(0) },
                        icon = MiuixIcons.Home,
                        label = stringResource(R.string.nav_home),
                    )
                    NavigationRailItem(
                        selected = selectedPage == 1,
                        onClick = { mainPagerState.animateToPage(1) },
                        icon = MiuixIcons.Tune,
                        label = stringResource(R.string.nav_proxy),
                    )
                    NavigationRailItem(
                        selected = selectedPage == 2,
                        onClick = { mainPagerState.animateToPage(2) },
                        icon = MiuixIcons.UploadCloud,
                        label = stringResource(R.string.nav_subscription),
                    )
                    NavigationRailItem(
                        selected = selectedPage == 3,
                        onClick = { mainPagerState.animateToPage(3) },
                        icon = MiuixIcons.Settings,
                        label = stringResource(R.string.nav_settings),
                    )
                }
                pagerContent(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // rail 已吸收起始侧的刘海/导航栏 inset；末尾侧无 rail，在此补齐并对后代标记为已消费
                        .consumeWindowInsets(
                            WindowInsets.displayCutout.union(WindowInsets.navigationBars)
                                .only(WindowInsetsSides.Start),
                        )
                        .windowInsetsPadding(
                            WindowInsets.systemBars.union(WindowInsets.displayCutout)
                                .only(WindowInsetsSides.End),
                        ),
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                )
            }
        }
    } else {
        val bottomBarBackdrop = rememberBlurBackdrop(themeConfig.blurEnabled)
        val bottomBarBlurActive = bottomBarBackdrop != null
        val barColor = if (bottomBarBlurActive) Color.Transparent else MiuixTheme.colorScheme.surface
        val floatingBarColor = if (bottomBarBlurActive) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer
        val floatingPillRadius = 50.dp
        val floatingBarShape = RoundedCornerShape(floatingPillRadius)
        val isDark = LocalAppDarkMode.current
        val floatingHighlight = remember(isDark) {
            if (isDark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight
        }
        val floatingBarModifier = if (bottomBarBackdrop != null) {
            Modifier.textureBlur(
                backdrop = bottomBarBackdrop,
                shape = floatingBarShape,
                blurRadius = 25f,
                colors = BlurDefaults.blurColors(
                    blendColors = listOf(
                        BlendColorEntry(
                            color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
                        ),
                    ),
                ),
                highlight = floatingHighlight,
            )
        } else {
            Modifier
        }
        val bottomBarDisplayMode = when (themeConfig.bottomBarMode) {
            BottomBarMode.IconAndText -> NavigationBarDisplayMode.IconAndText
            BottomBarMode.IconOnly -> NavigationBarDisplayMode.IconOnly
        }
        val showBottomBarLabels = themeConfig.bottomBarMode == BottomBarMode.IconAndText
        val navigationItems = listOf(
            NavigationItem(label = stringResource(R.string.nav_home), icon = MiuixIcons.Home),
            NavigationItem(label = stringResource(R.string.nav_proxy), icon = MiuixIcons.Tune),
            NavigationItem(label = stringResource(R.string.nav_subscription), icon = MiuixIcons.UploadCloud),
            NavigationItem(label = stringResource(R.string.nav_settings), icon = MiuixIcons.Settings),
        )

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                if (themeConfig.floatingBottomBar) {
                    if (themeConfig.floatingBottomBarStyle == FloatingBottomBarStyle.IosLike) {
                        IosLiquidGlassNavigationBar(
                            items = navigationItems,
                            selectedIndex = selectedPage,
                            onItemClick = { index -> mainPagerState.animateToPage(index) },
                            backdrop = bottomBarBackdrop,
                            isBlurActive = bottomBarBlurActive,
                            isDark = isDark,
                            showLabels = showBottomBarLabels,
                        )
                    } else {
                        FloatingNavigationBar(
                            modifier = floatingBarModifier,
                            color = floatingBarColor,
                            cornerRadius = floatingPillRadius,
                        ) {
                            navigationItems.forEachIndexed { index, item ->
                                MiuixFloatingNavigationBarItem(
                                    item = item,
                                    selected = selectedPage == index,
                                    onClick = { mainPagerState.animateToPage(index) },
                                    showLabel = showBottomBarLabels,
                                )
                            }
                        }
                    }
                } else {
                    // 渐进模糊仅作用于顶栏；底栏保持高斯
                    BlurredBar(
                        backdrop = bottomBarBackdrop,
                        blurActive = bottomBarBlurActive,
                        blurStyle = TopBarBlurStyle.Gaussian,
                    ) {
                        NavigationBar(
                            color = barColor,
                            mode = bottomBarDisplayMode,
                        ) {
                            navigationItems.forEachIndexed { index, item ->
                                NavigationBarItem(
                                    selected = selectedPage == index,
                                    onClick = { mainPagerState.animateToPage(index) },
                                    icon = item.icon,
                                    label = item.label,
                                )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            pagerContent(
                if (bottomBarBackdrop != null) {
                    Modifier
                        .fillMaxSize()
                        .layerBackdrop(bottomBarBackdrop)
                } else {
                    Modifier.fillMaxSize()
                },
                padding.calculateBottomPadding(),
            )
        }
    }
}

@Composable
private fun MiuixFloatingNavigationBarItem(
    item: NavigationItem,
    selected: Boolean,
    onClick: () -> Unit,
    showLabel: Boolean,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val onSurfaceContainerColor = MiuixTheme.colorScheme.onSurfaceContainer
    val tint = when {
        isPressed -> onSurfaceContainerColor.copy(alpha = if (selected) 0.7f else 0.5f)
        selected -> onSurfaceContainerColor
        else -> onSurfaceContainerColor.copy(alpha = 0.6f)
    }

    Column(
        modifier = modifier
            .defaultMinSize(minWidth = if (showLabel) 56.dp else 48.dp, minHeight = 48.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null,
            )
            .padding(horizontal = if (showLabel) 8.dp else 6.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            modifier = Modifier.size(22.dp),
            imageVector = item.icon,
            contentDescription = if (showLabel) null else item.label,
            tint = tint,
        )
        if (showLabel) {
            Text(
                text = item.label,
                color = tint,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// === MainPagerState ===

@Stable
class MainPagerState(
    val pagerState: PagerState,
    private val coroutineScope: CoroutineScope,
    private val animatePageChanges: Boolean,
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
                if (animatePageChanges) {
                    pagerState.springAnimateToPage(targetIndex)
                } else {
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

private suspend fun PagerState.springAnimateToPage(target: Int) {
    if (target !in 0 until pageCount) return
    var shouldSnapToTarget = false
    scroll(MutatePriority.UserInput) {
        val pageSize = layoutInfo.pageSize + layoutInfo.pageSpacing
        val distance = target - currentPage - currentPageOffsetFraction
        val scrollPixels = distance * pageSize
        if (abs(scrollPixels) <= 0.5f) return@scroll

        var consumedScroll = 0f
        var skipScroll = false
        Animatable(0f).animateTo(
            targetValue = scrollPixels,
            animationSpec = PagerNavigationSpringSpec,
        ) {
            if (skipScroll) return@animateTo

            val delta = value - consumedScroll
            if (abs(delta) > 0.5f) {
                val consumed = scrollBy(delta)
                consumedScroll += consumed
                if (abs(delta - consumed) > 0.1f) {
                    shouldSnapToTarget = true
                    skipScroll = true
                }
            } else {
                consumedScroll = value
            }

            if (abs(velocity) < 0.1f && abs(scrollPixels - consumedScroll) < 1.0f) {
                skipScroll = true
            }
        }

        val remaining = scrollPixels - consumedScroll
        if (abs(remaining) > 0.5f) {
            scrollBy(remaining)
        }
    }

    if (shouldSnapToTarget || currentPage != target) {
        scrollToPage(target)
    }
}

@Composable
fun rememberMainPagerState(
    pagerState: PagerState,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    animatePageChanges: Boolean = true,
): MainPagerState = remember(pagerState, coroutineScope, animatePageChanges) {
    MainPagerState(pagerState, coroutineScope, animatePageChanges)
}

// === 返回键处理 ===

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
