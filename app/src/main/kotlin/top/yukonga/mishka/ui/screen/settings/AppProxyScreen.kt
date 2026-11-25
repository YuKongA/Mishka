package top.yukonga.mishka.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import top.yukonga.mishka.R
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.BackNavigationIcon
import top.yukonga.mishka.ui.component.CardItem
import top.yukonga.mishka.ui.component.TopBarIconButton
import top.yukonga.mishka.ui.component.TopLayer
import top.yukonga.mishka.ui.component.blur.LocalBlurEnabled
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.LocalPageBackdrop
import top.yukonga.mishka.ui.component.blur.LocalTopBarExtraTopInset
import top.yukonga.mishka.ui.component.blur.PageBackdropBox
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.groupedCardItems
import top.yukonga.mishka.ui.component.overScrollVertical
import top.yukonga.mishka.ui.platform.AppIcon
import top.yukonga.mishka.ui.platform.getPlainText
import top.yukonga.mishka.ui.platform.setPlainText
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.viewmodel.AppProxyMode
import top.yukonga.mishka.viewmodel.AppProxyViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassIconDropdownMenu
import top.yukonga.miuix.kmp.glass.GlassSearchOverlay
import top.yukonga.miuix.kmp.glass.GlassSearchOverlayDefaults
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.glass.glassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPopupAnchorContent
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassSearchBarTransition
import top.yukonga.miuix.kmp.glass.rememberGlassSearchBarAlpha
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.More
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.icon.glass.Search as GlassSearch

@Composable
fun AppProxyScreen(
    viewModel: AppProxyViewModel,
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()
    val showPopup = remember { mutableStateOf(false) }
    val moreAnchor = rememberGlassPopupAnchor()
    var searchExpanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()

    val appliedMsg = stringResource(R.string.app_proxy_applied)
    val barAlpha = rememberGlassSearchBarAlpha(searchExpanded)
    LaunchedEffect(searchExpanded) {
        if (!searchExpanded) viewModel.setSearchQuery("")
    }
    val filteredApps by viewModel.filteredAppsFlow.collectAsStateWithLifecycle()

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val blurEnabled = LocalBlurEnabled.current
    val searchListState = rememberLazyListState()

    PageBackdropBox(modifier = Modifier.background(MiuixTheme.colorScheme.surface)) {
        Scaffold(
            modifier = Modifier
                .graphicsLayer { alpha = barAlpha.value }
                .then(if (searchExpanded) Modifier.clearAndSetSemantics { } else Modifier),
            topBar = {
                BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                    AdaptiveTopAppBar(
                        title = stringResource(R.string.app_proxy_title),
                        contentModifier = Modifier.glassSearchBarTransition(
                            expanded = searchExpanded,
                            blurEnabled = blurEnabled,
                        ) { 1f },
                        scrollBehavior = scrollBehavior,
                        navigationIcon = {
                            BackNavigationIcon(
                                onClick = {
                                    if (viewModel.applyIfChanged()) {
                                        showToast(appliedMsg)
                                    }
                                    onBack()
                                },
                            )
                        },
                        actions = {
                            TopBarIconButton(
                                onClick = { searchExpanded = true },
                                glassIcon = MiuixIcons.Glass.GlassSearch,
                                contentDescription = stringResource(R.string.app_proxy_search),
                            )
                            // 仅在非「允许所有应用」模式下显示菜单
                            if (uiState.mode != AppProxyMode.AllowAll) {
                                GlassIconButton(
                                    onClick = { showPopup.value = true },
                                    modifier = Modifier.glassPopupAnchor(
                                        anchor = moreAnchor,
                                        cornerRadius = GlassTopAppBarDefaults.ButtonSize / 2,
                                    ),
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Glass.More,
                                        contentDescription = stringResource(R.string.common_more),
                                        tint = MiuixTheme.colorScheme.onSurface,
                                        modifier = Modifier
                                            .size(28.dp)
                                            .glassPopupAnchorContent(moreAnchor),
                                    )
                                }
                            }
                        },
                    )
                }
            },
        ) { innerPadding ->
            val appList: @Composable () -> Unit = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                        .horizontalCutoutPadding()
                        .scrollEndHaptic()
                        .overScrollVertical(scrollBehavior)
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    overscrollEffect = null,
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                    ),
                ) {
                    groupedCardItems(
                        keyPrefix = "app_proxy_mode",
                        outerTopPadding = 6.dp,
                        outerBottomPadding = 6.dp,
                        items = listOf(
                            CardItem("allow_all") {
                                RadioButtonPreference(
                                    title = stringResource(R.string.app_proxy_allow_all),
                                    summary = stringResource(R.string.app_proxy_allow_all_summary),
                                    selected = uiState.mode == AppProxyMode.AllowAll,
                                    onClick = { viewModel.setMode(AppProxyMode.AllowAll) },
                                    radioButtonLocation = RadioButtonLocation.End,
                                )
                            },
                            CardItem("allow_selected") {
                                RadioButtonPreference(
                                    title = stringResource(R.string.app_proxy_allow_selected),
                                    summary = stringResource(R.string.app_proxy_allow_selected_summary),
                                    selected = uiState.mode == AppProxyMode.AllowSelected,
                                    onClick = { viewModel.setMode(AppProxyMode.AllowSelected) },
                                    radioButtonLocation = RadioButtonLocation.End,
                                )
                            },
                            CardItem("deny_selected") {
                                RadioButtonPreference(
                                    title = stringResource(R.string.app_proxy_deny_selected),
                                    summary = stringResource(R.string.app_proxy_deny_selected_summary),
                                    selected = uiState.mode == AppProxyMode.DenySelected,
                                    onClick = { viewModel.setMode(AppProxyMode.DenySelected) },
                                    radioButtonLocation = RadioButtonLocation.End,
                                )
                            },
                        ),
                    )

                    // 应用列表（AllowAll 模式下不显示）
                    if (uiState.mode != AppProxyMode.AllowAll) {
                        if (uiState.isLoading) {
                            // 加载中 → 进度指示器
                            item(key = "loading") {
                                Box(
                                    modifier = Modifier
                                        .fillParentMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    InfiniteProgressIndicator()
                                }
                            }
                        } else {
                            item(key = "apps_title") {
                                val selectedCount = remember(filteredApps, uiState.selectedPackages) {
                                    filteredApps.count { it.packageName in uiState.selectedPackages }
                                }
                                SmallTitle(
                                    text = stringResource(
                                        R.string.app_proxy_app_list,
                                        selectedCount,
                                        filteredApps.size
                                    )
                                )
                            }
                            items(
                                items = filteredApps,
                                key = { it.packageName },
                                contentType = { "app" },
                            ) { app ->
                                AppItem(
                                    appName = app.appName,
                                    packageName = app.packageName,
                                    isSelected = app.packageName in uiState.selectedPackages,
                                    onToggle = { viewModel.toggleApp(app.packageName) },
                                )
                            }
                        }
                    }

                    item {
                        Spacer(
                            Modifier
                                .height(24.dp)
                                .navigationBarsPadding()
                        )
                    }
                }
            }
            if (uiState.mode == AppProxyMode.AllowAll) {
                appList()
            } else {
                PullToRefresh(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = viewModel::refreshApps,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = innerPadding.calculateTopPadding() + 6.dp),
                    refreshTexts = listOf(
                        stringResource(R.string.refresh_pulling),
                        stringResource(R.string.refresh_release),
                        stringResource(R.string.refresh_refreshing),
                        stringResource(R.string.refresh_complete),
                    ),
                    content = appList,
                )
            }
        }
        val searchTopInsets = WindowInsets.systemBars
            .union(WindowInsets.captionBar)
            .union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Top)
        val searchTopPadding = LocalTopBarExtraTopInset.current
        val searchBarSpace = GlassSearchOverlayDefaults.BarHeight + GlassSearchOverlayDefaults.VerticalPadding * 2
        GlassSearchOverlay(
            expanded = searchExpanded,
            onDismissRequest = { searchExpanded = false },
            scrollMaterialThreshold = GlassSearchOverlayDefaults.VerticalPadding + 6.dp,
            label = stringResource(R.string.app_proxy_search),
            scrollOffset = {
                when {
                    searchListState.layoutInfo.totalItemsCount == 0 -> 0f
                    searchListState.firstVisibleItemIndex > 0 -> Float.MAX_VALUE
                    else -> searchListState.firstVisibleItemScrollOffset.toFloat()
                }
            },
            topInsets = searchTopInsets,
            searchTopPadding = searchTopPadding,
            blurEnabled = blurEnabled,
            horizontalInsetsModifier = Modifier.horizontalCutoutPadding(),
            searchIcon = MiuixIcons.Glass.GlassSearch,
            closeIcon = MiuixIcons.Glass.Close,
        ) { query ->
            LaunchedEffect(query) {
                viewModel.setSearchQuery(query)
                if (searchListState.firstVisibleItemIndex != 0 || searchListState.firstVisibleItemScrollOffset != 0) {
                    searchListState.scrollToItem(0)
                }
            }
            val topInsetsPadding = searchTopInsets.asPaddingValues()
            val searchContentPadding = remember(topInsetsPadding, searchTopPadding, searchBarSpace) {
                object : PaddingValues {
                    override fun calculateLeftPadding(layoutDirection: LayoutDirection) = 0.dp
                    override fun calculateRightPadding(layoutDirection: LayoutDirection) = 0.dp
                    override fun calculateTopPadding() =
                        topInsetsPadding.calculateTopPadding() + searchTopPadding() + searchBarSpace
                    override fun calculateBottomPadding() = 0.dp
                }
            }
            LazyColumn(
                state = searchListState,
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalCutoutPadding()
                    .overScrollVertical(),
                overscrollEffect = null,
                contentPadding = searchContentPadding,
            ) {
                item {
                    Spacer(Modifier.height(6.dp))
                }
                if (filteredApps.isEmpty()) {
                    item(key = "search_empty") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.app_proxy_no_match),
                                fontSize = 16.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                } else {
                    items(
                        items = filteredApps,
                        key = { it.packageName },
                        contentType = { "app" },
                    ) { app ->
                        AppItem(
                            appName = app.appName,
                            packageName = app.packageName,
                            isSelected = app.packageName in uiState.selectedPackages,
                            onToggle = { viewModel.toggleApp(app.packageName) },
                        )
                    }
                }
                item {
                    Spacer(
                        Modifier
                            .height(24.dp)
                            .navigationBarsPadding(),
                    )
                }
            }
        }
        if (uiState.mode != AppProxyMode.AllowAll) {
            val pageBackdrop = LocalPageBackdrop.current
            val selectAllLabel = stringResource(R.string.app_proxy_select_all)
            val deselectAllLabel = stringResource(R.string.app_proxy_deselect_all)
            val invertLabel = stringResource(R.string.app_proxy_invert)
            val systemLabel = if (uiState.showSystemApps) {
                stringResource(R.string.app_proxy_hide_system)
            } else {
                stringResource(R.string.app_proxy_show_system)
            }
            val importLabel = stringResource(R.string.app_proxy_import)
            val exportLabel = stringResource(R.string.app_proxy_export)
            val entries = remember(
                uiState.showSystemApps,
                selectAllLabel,
                deselectAllLabel,
                invertLabel,
                systemLabel,
                importLabel,
                exportLabel,
            ) {
                listOf(
                    DropdownEntry(
                        items = listOf(
                            DropdownItem(text = selectAllLabel, onClick = { viewModel.selectAll() }),
                            DropdownItem(text = deselectAllLabel, onClick = { viewModel.deselectAll() }),
                            DropdownItem(text = invertLabel, onClick = { viewModel.invertSelection() }),
                        ),
                    ),
                    DropdownEntry(
                        items = listOf(
                            DropdownItem(
                                text = systemLabel,
                                selected = uiState.showSystemApps,
                                onClick = { viewModel.setShowSystemApps(!uiState.showSystemApps) },
                            ),
                        ),
                    ),
                    DropdownEntry(
                        items = listOf(
                            DropdownItem(
                                text = importLabel,
                                onClick = {
                                    clipboardScope.launch {
                                        val text = clipboard.getPlainText()
                                        if (!text.isNullOrBlank()) {
                                            viewModel.importPackages(text)
                                        }
                                    }
                                },
                            ),
                            DropdownItem(
                                text = exportLabel,
                                onClick = {
                                    val exported = viewModel.exportPackages()
                                    if (exported.isNotEmpty()) {
                                        clipboardScope.launch { clipboard.setPlainText(exported) }
                                    }
                                },
                            ),
                        ),
                    ),
                )
            }
            TopLayer {
                GlassIconDropdownMenu(
                    show = showPopup.value,
                    onDismissRequest = { showPopup.value = false },
                    anchor = moreAnchor,
                    anchorContent = {
                        Icon(
                            imageVector = MiuixIcons.Glass.More,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(28.dp),
                        )
                    },
                    entries = entries,
                    backdrop = pageBackdrop,
                )
            }
        }
    }
}

@Composable
private fun AppItem(
    appName: String,
    packageName: String,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        BasicComponent(
            title = appName,
            summary = packageName,
            startAction = {
                AppIcon(
                    packageName = packageName,
                    modifier = Modifier.padding(end = 6.dp),
                    size = 40.dp,
                )
            },
            endActions = {
                Checkbox(
                    state = if (isSelected) ToggleableState.On else ToggleableState.Off,
                    onClick = onToggle,
                )
            },
            onClick = onToggle,
        )
    }
}
