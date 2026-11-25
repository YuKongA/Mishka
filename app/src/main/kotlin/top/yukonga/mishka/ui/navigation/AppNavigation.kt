package top.yukonga.mishka.ui.navigation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.computedStateOf
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.delay
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
import top.yukonga.mishka.ui.component.LocalTopLayer
import top.yukonga.mishka.ui.component.TopLayerHost
import top.yukonga.mishka.ui.component.TopLayerState
import top.yukonga.mishka.ui.component.blur.LocalCollapsedRailTopObstruction
import top.yukonga.mishka.ui.component.blur.LocalTopAppBarCenterObstructionStart
import top.yukonga.mishka.ui.component.blur.LocalTopAppBarTitleAlpha
import top.yukonga.mishka.ui.component.blur.LocalTopBarExtraTopInset
import top.yukonga.mishka.ui.component.blur.LocalTopBarStartInset
import top.yukonga.mishka.ui.component.blur.PageBackdropBox
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.liquid.IosLiquidGlassNavigationBar
import top.yukonga.mishka.ui.screen.connection.ConnectionScreen
import top.yukonga.mishka.ui.screen.dns.DnsQueryScreen
import top.yukonga.mishka.ui.screen.home.HomeScreen
import top.yukonga.mishka.ui.screen.log.LogScreen
import top.yukonga.mishka.ui.screen.overrides.OverrideEditScreen
import top.yukonga.mishka.ui.screen.overrides.OverrideFileEditorScreen
import top.yukonga.mishka.ui.screen.overrides.OverrideListScreen
import top.yukonga.mishka.ui.screen.overrides.SubscriptionOverridesScreen
import top.yukonga.mishka.ui.screen.provider.ProviderScreen
import top.yukonga.mishka.ui.screen.proxy.ProxyScreen
import top.yukonga.mishka.ui.screen.settings.AboutScreen
import top.yukonga.mishka.ui.screen.settings.AppProxyScreen
import top.yukonga.mishka.ui.screen.settings.AutomationScreen
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
import top.yukonga.mishka.ui.theme.FloatingBottomBarStyle
import top.yukonga.mishka.ui.theme.LocalAppDarkMode
import top.yukonga.mishka.ui.theme.LocalContainerSize
import top.yukonga.mishka.ui.theme.ThemeConfig
import top.yukonga.mishka.ui.util.DualPaneDividerWidth
import top.yukonga.mishka.ui.util.DualPaneMinWidth
import top.yukonga.mishka.ui.util.LocalHorizontalCutoutSides
import top.yukonga.mishka.ui.util.calculateDualPaneMasterWidth
import top.yukonga.mishka.ui.util.isInMultiWindowMode
import top.yukonga.mishka.ui.util.rememberHorizontalSafeInsets
import top.yukonga.mishka.ui.util.rememberIsWideScreen
import top.yukonga.mishka.viewmodel.AppProxyViewModel
import top.yukonga.mishka.viewmodel.AutomationViewModel
import top.yukonga.mishka.viewmodel.BackupViewModel
import top.yukonga.mishka.viewmodel.ConnectionViewModel
import top.yukonga.mishka.viewmodel.DnsQueryViewModel
import top.yukonga.mishka.viewmodel.ExternalControlViewModel
import top.yukonga.mishka.viewmodel.HomeUiState
import top.yukonga.mishka.viewmodel.HomeViewModel
import top.yukonga.mishka.viewmodel.LogViewModel
import top.yukonga.mishka.viewmodel.MetaSettingsViewModel
import top.yukonga.mishka.viewmodel.NetworkSettingsViewModel
import top.yukonga.mishka.viewmodel.OverrideProfileViewModel
import top.yukonga.mishka.viewmodel.ProviderViewModel
import top.yukonga.mishka.viewmodel.ProxyViewModel
import top.yukonga.mishka.viewmodel.SubscriptionViewModel
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassNavigationBar
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.glass.GlassNavigationRail
import top.yukonga.miuix.kmp.glass.GlassNavigationRailDefaults
import top.yukonga.miuix.kmp.glass.rememberGlassNavigationRailState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Cloud
import top.yukonga.miuix.kmp.icon.glass.ControlCenter
import top.yukonga.miuix.kmp.icon.glass.HomeFill
import top.yukonga.miuix.kmp.icon.glass.SettingsFill
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavEntryBuilder
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.PagerInterceptionMode
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.pagerGestureOverride
import top.yukonga.miuix.kmp.utils.springAnimateToPage
import kotlin.time.Duration.Companion.milliseconds

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

private val SettingsDetailStackSaver = Saver<SnapshotStateList<NavKey>, List<String>>(
    save = { stack ->
        stack.mapNotNull { key ->
            (key as? Route)?.let { Json.encodeToString<Route>(it) }
        }
    },
    restore = { savedRoutes ->
        mutableStateListOf<NavKey>().apply {
            savedRoutes.mapNotNull { value ->
                runCatching { Json.decodeFromString<Route>(value) }.getOrNull()
            }.let(::addAll)
        }
    },
)

val LocalMainPagerState = staticCompositionLocalOf<MainPagerState> {
    error("LocalMainPagerState not provided")
}

private val DefaultTopAppBarTitleAlpha: () -> Float = { 1f }
private val DefaultTopBarStartInset: () -> Dp = { 0.dp }

@Composable
fun AppNavigation(
    themeConfig: ThemeConfig = ThemeConfig(),
    onThemeConfigChange: (ThemeConfig) -> Unit = {},
    homeViewModel: HomeViewModel? = null,
    subscriptionViewModel: SubscriptionViewModel? = null,
    overrideProfileViewModel: OverrideProfileViewModel? = null,
    proxyViewModel: ProxyViewModel? = null,
    logViewModel: LogViewModel? = null,
    providerViewModel: ProviderViewModel? = null,
    connectionViewModel: ConnectionViewModel? = null,
    dnsQueryViewModel: DnsQueryViewModel? = null,
    networkSettingsViewModel: NetworkSettingsViewModel? = null,
    metaSettingsViewModel: MetaSettingsViewModel? = null,
    externalControlViewModel: ExternalControlViewModel? = null,
    automationViewModel: AutomationViewModel? = null,
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
    val settingsDetailStack = rememberSaveable(saver = SettingsDetailStackSaver) { mutableStateListOf() }
    val pagerState = rememberPagerState(pageCount = { 4 })
    val useNavigationRail = rememberIsWideScreen()
    val railState = if (useNavigationRail) {
        rememberGlassNavigationRailState(
            initialExpanded = storage?.getString(StorageKeys.NAV_RAIL_EXPANDED, "false") == "true",
        )
    } else {
        null
    }
    val mainPagerState = rememberMainPagerState(
        pagerState = pagerState,
        animatePageChanges = railState == null || !railState.expanded,
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

    val topLayer = remember { TopLayerState() }

    if (railState != null) {
        LaunchedEffect(railState.expanded, storage) {
            storage?.putString(StorageKeys.NAV_RAIL_EXPANDED, railState.expanded.toString())
        }
    }
    val sidebarBackdrop = if (useNavigationRail) rememberBlurBackdrop(themeConfig.blurEnabled) else null
    var suppressNavTransition by remember { mutableStateOf(false) }
    val containerWidthPx = LocalContainerSize.current?.width ?: LocalWindowInfo.current.containerSize.width
    val containerWidthDp = with(LocalDensity.current) { containerWidthPx.toDp() }
    val targetRailStartInset = if (railState != null && railState.expanded) {
        GlassNavigationRailDefaults.Margin + GlassNavigationRailDefaults.ExpandedWidth
    } else {
        0.dp
    }
    val safeInsets = rememberHorizontalSafeInsets()
    val isSettingsDualPane = (containerWidthDp - targetRailStartInset - safeInsets.start - safeInsets.end) >= DualPaneMinWidth
    LaunchedEffect(isSettingsDualPane) {
        if (isSettingsDualPane) {
            val settingsSuffix = backStack.drop(1).filterIsInstance<Route>().takeIf {
                it.isNotEmpty() && it.all(Route::isSettingsPaneRoute)
            }
            if (settingsSuffix != null) {
                suppressNavTransition = true
                settingsDetailStack.clear()
                settingsDetailStack.addAll(settingsSuffix)
                navigator.popUntil { it is Route.Main }
                mainPagerState.pagerState.scrollToPage(3)
                mainPagerState.syncPage()
            }
        } else if (
            navigator.current() is Route.Main &&
            mainPagerState.selectedPage == 3 &&
            settingsDetailStack.isNotEmpty()
        ) {
            suppressNavTransition = true
            settingsDetailStack.forEach { navigator.push(it) }
            settingsDetailStack.clear()
        }
    }
    SettingsDetailBackHandler(
        mainState = mainPagerState,
        navigator = navigator,
        settingsDetailStack = settingsDetailStack,
        isSettingsDualPane = isSettingsDualPane,
    )
    MainScreenBackHandler(mainPagerState, navigator, settingsDetailStack, isSettingsDualPane)
    LaunchedEffect(backStack.size, backStack.lastOrNull()) {
        if (suppressNavTransition) {
            delay(50.milliseconds)
            suppressNavTransition = false
        }
    }
    val navTransition = if (suppressNavTransition) InstantNavTransition else NavTransitions.MiuixDefault
    val settingsDetailRenderer: @Composable (Route, (Route) -> Unit, () -> Unit) -> Unit = { route, onPush, onPop ->
        when (route) {
            is Route.OverrideList -> overrideProfileViewModel?.let { vm ->
                OverrideListScreen(
                    viewModel = vm,
                    onBack = onPop,
                    onAdd = { onPush(Route.OverrideEdit()) },
                    onEdit = { id -> onPush(Route.OverrideEdit(id)) },
                    onEditFile = { id -> onPush(Route.OverrideFileEditor(id)) },
                )
            }

            is Route.OverrideEdit -> overrideProfileViewModel?.let { vm ->
                OverrideEditScreen(
                    overrideId = route.id,
                    viewModel = vm,
                    onBack = onPop,
                    onSaved = onPop,
                    onPickFile = { callback -> filePicker?.pickYamlFile(callback) },
                )
            }

            is Route.OverrideFileEditor -> overrideProfileViewModel?.let { vm ->
                OverrideFileEditorScreen(route.id, vm, onBack = onPop)
            }

            is Route.VpnSettings -> storage?.let {
                VpnSettingsScreen(
                    storage = it,
                    isSystemProxySupported = true,
                    onBack = onPop,
                )
            }

            is Route.RootSettings -> storage?.let {
                val homeState = homeViewModel?.uiState?.collectAsStateWithLifecycle()?.value
                RootSettingsScreen(
                    storage = it,
                    isProxyRunning = homeState?.isRunning == true || homeState?.isStarting == true,
                    onBack = onPop,
                )
            }

            is Route.NetworkSettings -> networkSettingsViewModel?.let {
                NetworkSettingsScreen(
                    viewModel = it,
                    onBack = onPop,
                )
            }

            is Route.MetaSettings -> metaSettingsViewModel?.let {
                MetaSettingsScreen(
                    viewModel = it,
                    onBack = onPop,
                )
            }

            is Route.AppProxy -> appProxyViewModel?.let {
                AppProxyScreen(
                    viewModel = it,
                    onBack = onPop,
                )
            }

            is Route.WifiPolicy -> storage?.let {
                WifiPolicyScreen(
                    storage = it,
                    controller = wifiPolicyController,
                    onRequestPermission = onRequestWifiPermission,
                    onBack = onPop,
                )
            }

            is Route.ThemeSettings -> storage?.let {
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
                    onBack = onPop,
                )
            }

            is Route.ExternalControl -> externalControlViewModel?.let {
                ExternalControlScreen(
                    viewModel = it,
                    onBack = onPop,
                )
            }

            is Route.Automation -> automationViewModel?.let {
                AutomationScreen(
                    viewModel = it,
                    onBack = onPop,
                )
            }

            is Route.BackupRestore -> if (backupViewModel != null && storage != null) {
                BackupRestoreScreen(
                    viewModel = backupViewModel,
                    storage = storage,
                    filePicker = filePicker,
                    onRestartApp = onRestartApp,
                    onBack = onPop,
                )
            }

            is Route.FileManager -> FileManagerScreen(
                subscriptionViewModel = subscriptionViewModel,
                onBack = onPop,
                onOpenFile = { uuid, relPath ->
                    onPush(Route.FileManagerEditor(uuid, relPath))
                },
            )

            is Route.FileManagerEditor -> FileManagerEditorScreen(
                uuid = route.uuid,
                relativePath = route.relativePath,
                subscriptionViewModel = subscriptionViewModel,
                onBack = onPop,
            )

            is Route.About -> {
                val uriHandler = LocalUriHandler.current
                AboutScreen(
                    onBack = onPop,
                    mihomoVersion = mihomoVersion,
                    onOpenUrl = { url -> uriHandler.openUri(url) },
                )
            }

            else -> Unit
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
    ) {
        CompositionLocalProvider(
            LocalTopLayer provides topLayer,
            LocalNavigator provides navigator,
            LocalMainPagerState provides mainPagerState,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (useNavigationRail) Modifier.background(MiuixTheme.colorScheme.surface) else Modifier)
                    .then(if (useNavigationRail) Modifier.clipToBounds() else Modifier),
            ) {
                PageBackdropBox(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (sidebarBackdrop != null) Modifier.layerBackdrop(sidebarBackdrop) else Modifier),
                ) {
                    val topAppBarTitleAlpha = remember(railState) {
                        if (railState != null) {
                            { railState.progress }
                        } else {
                            DefaultTopAppBarTitleAlpha
                        }
                    }
                    val topBarStartInset = remember(railState) {
                        if (railState != null) {
                            { (GlassNavigationRailDefaults.Margin + GlassNavigationRailDefaults.ExpandedWidth) * railState.progress }
                        } else {
                            DefaultTopBarStartInset
                        }
                    }
                    val collapsedRailTopObstruction = remember(railState) {
                        if (railState != null) {
                            { 56.dp * (1f - railState.progress) }
                        } else {
                            DefaultTopBarStartInset
                        }
                    }
                    CompositionLocalProvider(
                        LocalTopAppBarTitleAlpha provides topAppBarTitleAlpha,
                        LocalTopAppBarCenterObstructionStart provides (if (
                            railState != null && !railState.expanded && railState.collapsedWidth > 0.dp
                        ) {
                            railState.collapsedStart
                        } else {
                            Dp.Unspecified
                        }),
                        LocalTopBarStartInset provides topBarStartInset,
                        LocalCollapsedRailTopObstruction provides collapsedRailTopObstruction,
                    ) {
                        val navCornerRadius = if (isInMultiWindowMode()) 0.dp else rememberNavSystemCornerRadius()
                        NavDisplay(
                            backStack = backStack,
                            onBack = { navigator.pop() },
                            transition = navTransition,
                            effects = NavDisplayEffects(
                                cornerClipRadius = navCornerRadius,
                            ),
                        ) {
                            entry<Route.Main>(swipeDismiss = swipeDismiss) {
                                CompositionLocalProvider(
                                    LocalTopAppBarCenterObstructionStart provides Dp.Unspecified,
                                ) {
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
                                        settingsDetailStack = settingsDetailStack,
                                        settingsDetailRenderer = settingsDetailRenderer,
                                        isSettingsDualPane = isSettingsDualPane,
                                        swipeDismiss = swipeDismiss,
                                    )
                                }
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
                                        onNavigateOverrides = { navigator.push(Route.SubscriptionOverrides(route.uuid)) },
                                    )
                                }
                            }
                            entry<Route.SubscriptionOverrides>(swipeDismiss = swipeDismiss) { route ->
                                if (subscriptionViewModel != null && overrideProfileViewModel != null) {
                                    SubscriptionOverridesScreen(
                                        subscriptionId = route.uuid,
                                        subscriptionViewModel = subscriptionViewModel,
                                        overrideViewModel = overrideProfileViewModel,
                                        onBack = { navigator.pop() },
                                    )
                                }
                            }
                            registerSettingsEntries(
                                swipeDismiss = swipeDismiss,
                                onPush = { navigator.push(it) },
                                onPop = { navigator.pop() },
                                settingsDetailRenderer = settingsDetailRenderer,
                            )
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
                        }
                    }
                }
                if (railState != null) {
                    val homeLabel = stringResource(R.string.nav_home)
                    val proxyLabel = stringResource(R.string.nav_proxy)
                    val subscriptionLabel = stringResource(R.string.nav_subscription)
                    val settingsLabel = stringResource(R.string.nav_settings)
                    val glassItems = remember(homeLabel, proxyLabel, subscriptionLabel, settingsLabel) {
                        listOf(
                            GlassNavigationItem(icon = MiuixIcons.Glass.HomeFill, label = homeLabel, contentDescription = homeLabel),
                            GlassNavigationItem(icon = MiuixIcons.Glass.ControlCenter, label = proxyLabel, contentDescription = proxyLabel),
                            GlassNavigationItem(
                                icon = MiuixIcons.Glass.Cloud,
                                label = subscriptionLabel,
                                contentDescription = subscriptionLabel,
                            ),
                            GlassNavigationItem(
                                icon = MiuixIcons.Glass.SettingsFill,
                                label = settingsLabel,
                                contentDescription = settingsLabel
                            ),
                        )
                    }
                    val currentRoute = backStack.lastOrNull()
                    GlassNavigationRail(
                        items = glassItems,
                        selectedIndex = if (currentRoute is Route.Main) mainPagerState.selectedPage else -1,
                        onSelect = { index ->
                            if (currentRoute !is Route.Main) {
                                suppressNavTransition = true
                                navigator.popUntil { route -> route is Route.Main }
                            }
                            mainPagerState.animateToPage(index)
                        },
                        backdrop = sidebarBackdrop,
                        state = railState,
                    )
                }
                }
                TopLayerHost(topLayer)
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
    settingsDetailStack: SnapshotStateList<NavKey> = remember { mutableStateListOf() },
    settingsDetailRenderer: @Composable (Route, (Route) -> Unit, () -> Unit) -> Unit = { _, _, _ -> },
    isSettingsDualPane: Boolean = false,
    swipeDismiss: NavSwipeDirection? = null,
) {
    val homeUiState = homeViewModel?.uiState?.collectAsStateWithLifecycle()?.value ?: HomeUiState()
    val selectedPage = mainPagerState.selectedPage
    val interceptPagerGestures =
        themeConfig.pagerInterceptionMode == PagerInterceptionMode.CrossAxis
    val nativePagerNestedScrollConnection = PagerDefaults.pageNestedScrollConnection(
        state = mainPagerState.pagerState,
        orientation = Orientation.Horizontal,
    )
    val pagerFlingBehavior = PagerDefaults.flingBehavior(
        state = mainPagerState.pagerState,
        snapAnimationSpec = PagerNavigationSpringSpec,
    )

    // 页面主体：手机与宽屏两套外壳共用，仅传入不同的容器 modifier 与底部留白
    val pagerContent: @Composable (Modifier, Dp) -> Unit = { pagerModifier, bottomPadding ->
        HorizontalPager(
            modifier = pagerModifier.pagerGestureOverride(
                pagerState = mainPagerState.pagerState,
                flingBehavior = pagerFlingBehavior,
                mode = themeConfig.pagerInterceptionMode,
            ),
            state = mainPagerState.pagerState,
            userScrollEnabled = !interceptPagerGestures,
            pageNestedScrollConnection = if (interceptPagerGestures) {
                PagerGestureNestedScrollConnection
            } else {
                nativePagerNestedScrollConnection
            },
            verticalAlignment = Alignment.Top,
            flingBehavior = pagerFlingBehavior,
            overscrollEffect = null,
        ) { page ->
            when (page) {
                0 -> HomeScreen(
                    bottomPadding = bottomPadding,
                    uiState = homeUiState,
                    viewModel = homeViewModel,
                    onRestart = { homeViewModel?.restartProxy() },
                    onStop = { homeViewModel?.stopProxy() },
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

                3 -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val safeInsets = rememberHorizontalSafeInsets()
                    val startInset = LocalTopBarStartInset.current()
                    val availableWidth = (maxWidth - startInset - safeInsets.start - safeInsets.end).coerceAtLeast(0.dp)
                    val masterPaneWidth = calculateDualPaneMasterWidth(availableWidth) + startInset + safeInsets.start
                    var suppressDetailTransition by remember { mutableStateOf(false) }
                    LaunchedEffect(suppressDetailTransition) {
                        if (suppressDetailTransition) {
                            delay(50.milliseconds)
                            suppressDetailTransition = false
                        }
                    }
                    val navigateSettings: (Route) -> Unit = { route ->
                        if (isSettingsDualPane) {
                            if (settingsDetailStack.size != 1 || settingsDetailStack.firstOrNull() != route) {
                                suppressDetailTransition = true
                                settingsDetailStack.clear()
                                settingsDetailStack.add(route)
                            }
                        } else {
                            navigator.push(route)
                        }
                    }
                    if (isSettingsDualPane) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .width(masterPaneWidth)
                                    .clipToBounds()
                                    .fillMaxHeight(),
                            ) {
                                CompositionLocalProvider(
                                    LocalHorizontalCutoutSides provides WindowInsetsSides.Start,
                                ) {
                                    SettingsScreen(
                                        bottomPadding = bottomPadding,
                                        selectedRoute = settingsDetailStack.firstOrNull() as? Route,
                                        onNavigateVpnSettings = { navigateSettings(Route.VpnSettings) },
                                        onNavigateRootSettings = { navigateSettings(Route.RootSettings) },
                                        onNavigateNetworkSettings = { navigateSettings(Route.NetworkSettings) },
                                        onNavigateMetaSettings = { navigateSettings(Route.MetaSettings) },
                                        onNavigateExternalControl = { navigateSettings(Route.ExternalControl) },
                                        onNavigateAutomation = { navigateSettings(Route.Automation) },
                                        onNavigateAppProxy = { navigateSettings(Route.AppProxy) },
                                        onNavigateWifiPolicy = { navigateSettings(Route.WifiPolicy) },
                                        onNavigateThemeSettings = { navigateSettings(Route.ThemeSettings) },
                                        onNavigateFileManager = { navigateSettings(Route.FileManager) },
                                        onNavigateOverrides = { navigateSettings(Route.OverrideList) },
                                        onNavigateBackup = { navigateSettings(Route.BackupRestore) },
                                        onNavigateAbout = { navigateSettings(Route.About) },
                                        bootStartManager = bootStartManager,
                                        storage = storage,
                                        onHideTaskCardChange = onHideTaskCardChange,
                                        hasRootPermission = hasRootPermission,
                                        isProxyRunning = homeUiState.isRunning || homeUiState.isStarting,
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .width(DualPaneDividerWidth)
                                    .fillMaxHeight()
                                    .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clipToBounds()
                                    .fillMaxHeight(),
                            ) {
                                CompositionLocalProvider(
                                    LocalTopBarStartInset provides DefaultTopBarStartInset,
                                    LocalTopAppBarCenterObstructionStart provides Dp.Unspecified,
                                    LocalTopAppBarTitleAlpha provides DefaultTopAppBarTitleAlpha,
                                    LocalHorizontalCutoutSides provides WindowInsetsSides.End,
                                    LocalTopBarExtraTopInset provides LocalCollapsedRailTopObstruction.current,
                                ) {
                                    if (settingsDetailStack.isEmpty()) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = stringResource(R.string.settings_select_item_hint),
                                                fontSize = 15.sp,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            )
                                        }
                                    } else {
                                        val detailTransition = if (suppressDetailTransition) {
                                            InstantNavTransition
                                        } else {
                                            NavTransitions.MiuixDefault
                                        }
                                        val detailSwipeDismiss = if (settingsDetailStack.size > 1) swipeDismiss else null
                                        NavDisplay(
                                            backStack = settingsDetailStack,
                                            onBack = {
                                                if (settingsDetailStack.isNotEmpty()) {
                                                    settingsDetailStack.removeAt(settingsDetailStack.lastIndex)
                                                }
                                            },
                                            transition = detailTransition,
                                            effects = NavDisplayEffects(cornerClipRadius = 0.dp),
                                        ) {
                                            registerSettingsEntries(
                                                swipeDismiss = detailSwipeDismiss,
                                                onPush = { sub ->
                                                    if (sub !in settingsDetailStack) {
                                                        suppressDetailTransition = false
                                                        settingsDetailStack.add(sub)
                                                    }
                                                },
                                                onPop = {
                                                    if (settingsDetailStack.isNotEmpty()) {
                                                        suppressDetailTransition = false
                                                        settingsDetailStack.removeAt(settingsDetailStack.lastIndex)
                                                    }
                                                },
                                                settingsDetailRenderer = settingsDetailRenderer,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        SettingsScreen(
                            bottomPadding = bottomPadding,
                            onNavigateVpnSettings = { navigateSettings(Route.VpnSettings) },
                            onNavigateRootSettings = { navigateSettings(Route.RootSettings) },
                            onNavigateNetworkSettings = { navigateSettings(Route.NetworkSettings) },
                            onNavigateMetaSettings = { navigateSettings(Route.MetaSettings) },
                            onNavigateExternalControl = { navigateSettings(Route.ExternalControl) },
                            onNavigateAutomation = { navigateSettings(Route.Automation) },
                            onNavigateAppProxy = { navigateSettings(Route.AppProxy) },
                            onNavigateWifiPolicy = { navigateSettings(Route.WifiPolicy) },
                            onNavigateThemeSettings = { navigateSettings(Route.ThemeSettings) },
                            onNavigateFileManager = { navigateSettings(Route.FileManager) },
                            onNavigateOverrides = { navigateSettings(Route.OverrideList) },
                            onNavigateBackup = { navigateSettings(Route.BackupRestore) },
                            onNavigateAbout = { navigateSettings(Route.About) },
                            bootStartManager = bootStartManager,
                            storage = storage,
                            onHideTaskCardChange = onHideTaskCardChange,
                            hasRootPermission = hasRootPermission,
                            isProxyRunning = homeUiState.isRunning || homeUiState.isStarting,
                        )
                    }
                }
            }
        }
    }

        val homeLabel = stringResource(R.string.nav_home)
        val proxyLabel = stringResource(R.string.nav_proxy)
        val subscriptionLabel = stringResource(R.string.nav_subscription)
        val settingsLabel = stringResource(R.string.nav_settings)

        if (useNavigationRail) {
            val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            pagerContent(Modifier.fillMaxSize(), bottomInset)
        } else {
            val bottomBarBackdrop = rememberBlurBackdrop(themeConfig.blurEnabled)
            val bottomBarBlurActive = bottomBarBackdrop != null
            val navigationItems = remember(homeLabel, proxyLabel, subscriptionLabel, settingsLabel) {
                listOf(
                    NavigationItem(label = homeLabel, icon = MiuixIcons.Glass.HomeFill),
                    NavigationItem(label = proxyLabel, icon = MiuixIcons.Glass.ControlCenter),
                    NavigationItem(label = subscriptionLabel, icon = MiuixIcons.Glass.Cloud),
                    NavigationItem(label = settingsLabel, icon = MiuixIcons.Glass.SettingsFill),
                )
            }
            val glassNavigationItems = remember(homeLabel, proxyLabel, subscriptionLabel, settingsLabel) {
                listOf(
                    GlassNavigationItem(
                        icon = MiuixIcons.Glass.HomeFill,
                        label = homeLabel,
                        contentDescription = homeLabel,
                    ),
                    GlassNavigationItem(
                        icon = MiuixIcons.Glass.ControlCenter,
                        label = proxyLabel,
                        contentDescription = proxyLabel,
                    ),
                    GlassNavigationItem(
                        icon = MiuixIcons.Glass.Cloud,
                        label = subscriptionLabel,
                        contentDescription = subscriptionLabel,
                    ),
                    GlassNavigationItem(
                        icon = MiuixIcons.Glass.SettingsFill,
                        label = settingsLabel,
                        contentDescription = settingsLabel,
                    ),
                )
            }

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                bottomBar = {
                    when (themeConfig.floatingBottomBarStyle) {
                        FloatingBottomBarStyle.Glass -> {
                            GlassNavigationBar(
                                items = glassNavigationItems,
                                selectedIndex = selectedPage,
                                onSelect = { mainPagerState.animateToPage(it) },
                                backdrop = if (bottomBarBlurActive) bottomBarBackdrop else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp),
                            )
                        }

                        FloatingBottomBarStyle.IosLike -> {
                            IosLiquidGlassNavigationBar(
                                items = navigationItems,
                                selectedIndex = selectedPage,
                                onItemClick = { index -> mainPagerState.animateToPage(index) },
                                backdrop = bottomBarBackdrop,
                                isBlurActive = bottomBarBlurActive,
                                isDark = LocalAppDarkMode.current,
                                showLabels = true,
                            )
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
private fun SettingsDetailBackHandler(
    mainState: MainPagerState,
    navigator: Navigator,
    settingsDetailStack: SnapshotStateList<NavKey>,
    isSettingsDualPane: Boolean,
) {
    val isEnabled by remember(settingsDetailStack, isSettingsDualPane) {
        computedStateOf {
            navigator.current() is Route.Main &&
                    navigator.backStackSize() == 1 &&
                    mainState.selectedPage == 3 &&
                    isSettingsDualPane &&
                    settingsDetailStack.isNotEmpty()
        }
    }
    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = isEnabled,
        onBackCompleted = {
            if (settingsDetailStack.isNotEmpty()) {
                settingsDetailStack.removeAt(settingsDetailStack.lastIndex)
            }
        },
    )
}

@Composable
private fun MainScreenBackHandler(
    mainState: MainPagerState,
    navigator: Navigator,
    settingsDetailStack: SnapshotStateList<NavKey>,
    isSettingsDualPane: Boolean,
) {
    val isPagerBackHandlerEnabled by remember(settingsDetailStack, isSettingsDualPane) {
        computedStateOf {
            navigator.current() is Route.Main &&
                    navigator.backStackSize() == 1 &&
                    mainState.selectedPage != 0 &&
                    !(mainState.selectedPage == 3 && isSettingsDualPane && settingsDetailStack.isNotEmpty())
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

private val InstantNavTransition: NavTransition = navGraphicsTransition(
    opaqueDepth = 1f,
    dismissDirection = NavSwipeDirection.None,
    motion = NavMotion(
        programmatic = NavSettleSpec.Tween(durationMillis = 0, easing = LinearEasing),
    ),
) {}

private fun Route.isSettingsPaneRoute(): Boolean = when (this) {
    is Route.VpnSettings,
    is Route.RootSettings,
    is Route.NetworkSettings,
    is Route.MetaSettings,
    is Route.ExternalControl,
    is Route.Automation,
    is Route.AppProxy,
    is Route.WifiPolicy,
    is Route.ThemeSettings,
    is Route.FileManager,
    is Route.FileManagerEditor,
    is Route.OverrideList,
    is Route.OverrideEdit,
    is Route.OverrideFileEditor,
    is Route.BackupRestore,
    is Route.About,
        -> true

    else -> false
}

private fun NavEntryBuilder.registerSettingsEntries(
    swipeDismiss: NavSwipeDirection?,
    onPush: (Route) -> Unit,
    onPop: () -> Unit,
    settingsDetailRenderer: @Composable (Route, (Route) -> Unit, () -> Unit) -> Unit,
) {
    entry<Route.OverrideList>(swipeDismiss = swipeDismiss) { route -> settingsDetailRenderer(route, onPush, onPop) }
    entry<Route.OverrideEdit>(swipeDismiss = swipeDismiss) { route -> settingsDetailRenderer(route, onPush, onPop) }
    entry<Route.OverrideFileEditor>(swipeDismiss = swipeDismiss) { route -> settingsDetailRenderer(route, onPush, onPop) }
    entry<Route.VpnSettings>(swipeDismiss = swipeDismiss) { route -> settingsDetailRenderer(route, onPush, onPop) }
    entry<Route.RootSettings>(swipeDismiss = swipeDismiss) { route -> settingsDetailRenderer(route, onPush, onPop) }
    entry<Route.NetworkSettings>(swipeDismiss = swipeDismiss) { route -> settingsDetailRenderer(route, onPush, onPop) }
    entry<Route.MetaSettings>(swipeDismiss = swipeDismiss) { route -> settingsDetailRenderer(route, onPush, onPop) }
    entry<Route.AppProxy>(swipeDismiss = swipeDismiss) { route -> settingsDetailRenderer(route, onPush, onPop) }
    entry<Route.WifiPolicy>(swipeDismiss = swipeDismiss) { route -> settingsDetailRenderer(route, onPush, onPop) }
    entry<Route.ThemeSettings>(swipeDismiss = swipeDismiss) { route -> settingsDetailRenderer(route, onPush, onPop) }
    entry<Route.ExternalControl>(swipeDismiss = swipeDismiss) { route -> settingsDetailRenderer(route, onPush, onPop) }
    entry<Route.Automation>(swipeDismiss = swipeDismiss) { route -> settingsDetailRenderer(route, onPush, onPop) }
    entry<Route.BackupRestore>(swipeDismiss = swipeDismiss) { route -> settingsDetailRenderer(route, onPush, onPop) }
    entry<Route.FileManager>(swipeDismiss = swipeDismiss) { route -> settingsDetailRenderer(route, onPush, onPop) }
    entry<Route.FileManagerEditor>(swipeDismiss = swipeDismiss) { route -> settingsDetailRenderer(route, onPush, onPop) }
    entry<Route.About>(swipeDismiss = swipeDismiss) { route -> settingsDetailRenderer(route, onPush, onPop) }
}
