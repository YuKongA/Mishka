package top.yukonga.mishka.ui.screen.settings

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.app_proxy_allow_all
import mishka.shared.generated.resources.app_proxy_allow_all_summary
import mishka.shared.generated.resources.app_proxy_allow_selected
import mishka.shared.generated.resources.app_proxy_allow_selected_summary
import mishka.shared.generated.resources.app_proxy_app_list
import mishka.shared.generated.resources.app_proxy_deny_selected
import mishka.shared.generated.resources.app_proxy_deny_selected_summary
import mishka.shared.generated.resources.app_proxy_deselect_all
import mishka.shared.generated.resources.app_proxy_export
import mishka.shared.generated.resources.app_proxy_hide_system
import mishka.shared.generated.resources.app_proxy_import
import mishka.shared.generated.resources.app_proxy_invert
import mishka.shared.generated.resources.app_proxy_mode
import mishka.shared.generated.resources.app_proxy_no_match
import mishka.shared.generated.resources.app_proxy_search
import mishka.shared.generated.resources.app_proxy_select_all
import mishka.shared.generated.resources.app_proxy_show_system
import mishka.shared.generated.resources.app_proxy_title
import mishka.shared.generated.resources.common_back
import mishka.shared.generated.resources.common_more
import org.jetbrains.compose.resources.stringResource
import top.yukonga.mishka.platform.AppIcon
import top.yukonga.mishka.ui.component.ListPopupDefaults.MenuPositionProvider
import top.yukonga.mishka.ui.component.SearchBarFake
import top.yukonga.mishka.ui.component.SearchBox
import top.yukonga.mishka.ui.component.SearchPager
import top.yukonga.mishka.ui.component.SearchStatus
import top.yukonga.mishka.ui.util.rememberContentReady
import top.yukonga.mishka.viewmodel.AppProxyMode
import top.yukonga.mishka.viewmodel.AppProxyViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowListPopup

@Composable
fun AppProxyScreen(
    viewModel: AppProxyViewModel,
    onBack: () -> Unit = {},
    bottomPadding: Dp = 0.dp,
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollBehavior = MiuixScrollBehavior()
    val showPopup = remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val clipboardManager = LocalClipboardManager.current

    val searchLabel = stringResource(Res.string.app_proxy_search)
    var searchStatus by remember { mutableStateOf(SearchStatus(label = searchLabel)) }

    // 语言变更时同步 label
    LaunchedEffect(searchLabel) {
        if (searchStatus.label != searchLabel) {
            searchStatus = searchStatus.copy(label = searchLabel)
        }
    }

    // 搜索过滤（直接传入 searchText，避免组合期间写入 ViewModel 状态）
    val searchText = searchStatus.searchText

    // 同步搜索词到 ViewModel（selectAll/invertSelection 等操作需要）
    LaunchedEffect(searchText) {
        viewModel.setSearchQuery(searchText)
    }

    val filteredApps = remember(searchText, uiState.apps, uiState.showSystemApps, uiState.selectedPackages) {
        viewModel.filteredApps(searchText)
    }

    // 更新搜索结果状态
    val resultStatus by remember(searchText, filteredApps) {
        derivedStateOf {
            when {
                searchText.isEmpty() -> SearchStatus.ResultStatus.DEFAULT
                filteredApps.isEmpty() -> SearchStatus.ResultStatus.EMPTY
                else -> SearchStatus.ResultStatus.SHOW
            }
        }
    }
    LaunchedEffect(resultStatus) {
        if (searchStatus.resultStatus != resultStatus) {
            searchStatus = searchStatus.copy(resultStatus = resultStatus)
        }
    }

    val dynamicTopPadding by remember {
        derivedStateOf { 12.dp * (1f - scrollBehavior.state.collapsedFraction) }
    }

    Scaffold(
        topBar = {
            searchStatus.TopAppBarAnim {
                TopAppBar(
                    title = stringResource(Res.string.app_proxy_title),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = {
                            viewModel.applyIfChanged()
                            onBack()
                        }) {
                            val layoutDirection = LocalLayoutDirection.current
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(Res.string.common_back),
                                tint = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                                },
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { showPopup.value = true },
                            holdDownState = showPopup.value,
                        ) {
                            Icon(
                                imageVector = MiuixIcons.More,
                                contentDescription = stringResource(Res.string.common_more),
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }

                        // 顶栏下拉菜单
                        WindowListPopup(
                            show = showPopup.value,
                            popupPositionProvider = MenuPositionProvider,
                            alignment = PopupPositionProvider.Align.TopEnd,
                            onDismissRequest = { showPopup.value = false },
                        ) {
                            ListPopupColumn {
                                DropdownImpl(
                                    text = stringResource(Res.string.app_proxy_select_all),
                                    optionSize = 6,
                                    isSelected = false,
                                    index = 0,
                                    onSelectedIndexChange = {
                                        viewModel.selectAll()
                                        showPopup.value = false
                                    },
                                )
                                DropdownImpl(
                                    text = stringResource(Res.string.app_proxy_deselect_all),
                                    optionSize = 6,
                                    isSelected = false,
                                    index = 1,
                                    onSelectedIndexChange = {
                                        viewModel.deselectAll()
                                        showPopup.value = false
                                    },
                                )
                                DropdownImpl(
                                    text = stringResource(Res.string.app_proxy_invert),
                                    optionSize = 6,
                                    isSelected = false,
                                    index = 2,
                                    onSelectedIndexChange = {
                                        viewModel.invertSelection()
                                        showPopup.value = false
                                    },
                                )
                                HorizontalDivider(
                                    modifier = Modifier
                                        .padding(horizontal = 20.dp)
                                        .fillMaxWidth(),
                                )
                                DropdownImpl(
                                    text = if (uiState.showSystemApps) stringResource(Res.string.app_proxy_hide_system) else stringResource(Res.string.app_proxy_show_system),
                                    optionSize = 6,
                                    isSelected = uiState.showSystemApps,
                                    index = 3,
                                    onSelectedIndexChange = {
                                        viewModel.setShowSystemApps(!uiState.showSystemApps)
                                        showPopup.value = false
                                    },
                                )
                                HorizontalDivider(
                                    modifier = Modifier
                                        .padding(horizontal = 20.dp)
                                        .fillMaxWidth(),
                                )
                                DropdownImpl(
                                    text = stringResource(Res.string.app_proxy_import),
                                    optionSize = 6,
                                    isSelected = false,
                                    index = 4,
                                    onSelectedIndexChange = {
                                        val text = clipboardManager.getText()?.text
                                        if (!text.isNullOrBlank()) {
                                            viewModel.importPackages(text)
                                        }
                                        showPopup.value = false
                                    },
                                )
                                DropdownImpl(
                                    text = stringResource(Res.string.app_proxy_export),
                                    optionSize = 6,
                                    isSelected = false,
                                    index = 5,
                                    onSelectedIndexChange = {
                                        val exported = viewModel.exportPackages()
                                        if (exported.isNotEmpty()) {
                                            clipboardManager.setText(AnnotatedString(exported))
                                        }
                                        showPopup.value = false
                                    },
                                )
                            }
                        }
                    },
                    bottomContent = {
                        Box(
                            modifier = Modifier
                                .alpha(if (searchStatus.isCollapsed()) 1f else 0f)
                                .onGloballyPositioned { coordinates ->
                                    with(density) {
                                        val newOffsetY = coordinates.positionInWindow().y.toDp()
                                        if (searchStatus.offsetY != newOffsetY) {
                                            searchStatus = searchStatus.copy(offsetY = newOffsetY)
                                        }
                                    }
                                }
                                .then(
                                    if (searchStatus.isCollapsed()) {
                                        Modifier.pointerInput(Unit) {
                                            detectTapGestures {
                                                searchStatus = searchStatus.copy(current = SearchStatus.Status.EXPANDING)
                                            }
                                        }
                                    } else Modifier,
                                ),
                        ) {
                            SearchBarFake(searchStatus.label, dynamicTopPadding)
                        }
                    },
                )
            }
        },
        popupHost = {
            searchStatus.SearchPager(
                onSearchStatusChange = { searchStatus = it },
                defaultResult = {},
                searchBarTopPadding = dynamicTopPadding,
            ) {
                // 搜索结果列表
                val imeBottomPadding = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .overScrollVertical(),
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
                                    text = stringResource(Res.string.app_proxy_no_match),
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
                        Spacer(Modifier.height(maxOf(bottomPadding, imeBottomPadding)))
                    }
                }
            }
        },
    ) { innerPadding ->
        val contentReady = rememberContentReady()

        searchStatus.SearchBox {
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
                // 代理模式
                item(key = "mode_title") { SmallTitle(text = stringResource(Res.string.app_proxy_mode)) }
                item(key = "mode_card") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 6.dp),
                    ) {
                        RadioButtonPreference(
                            title = stringResource(Res.string.app_proxy_allow_all),
                            summary = stringResource(Res.string.app_proxy_allow_all_summary),
                            selected = uiState.mode == AppProxyMode.AllowAll,
                            onClick = { viewModel.setMode(AppProxyMode.AllowAll) },
                        )
                        RadioButtonPreference(
                            title = stringResource(Res.string.app_proxy_allow_selected),
                            summary = stringResource(Res.string.app_proxy_allow_selected_summary),
                            selected = uiState.mode == AppProxyMode.AllowSelected,
                            onClick = { viewModel.setMode(AppProxyMode.AllowSelected) },
                        )
                        RadioButtonPreference(
                            title = stringResource(Res.string.app_proxy_deny_selected),
                            summary = stringResource(Res.string.app_proxy_deny_selected_summary),
                            selected = uiState.mode == AppProxyMode.DenySelected,
                            onClick = { viewModel.setMode(AppProxyMode.DenySelected) },
                        )
                    }
                }

                // 应用列表（AllowAll 模式下不显示）
                if (uiState.mode != AppProxyMode.AllowAll) {
                    if (!contentReady || uiState.isLoading) {
                        // 导航动画中或加载中 → 进度指示器
                        item(key = "loading") {
                            Box(
                                modifier = Modifier
                                    .fillParentMaxSize()
                                    .padding(bottom = bottomPadding),
                                contentAlignment = Alignment.Center,
                            ) {
                                InfiniteProgressIndicator()
                            }
                        }
                    } else {
                        item(key = "apps_title") {
                            SmallTitle(text = stringResource(Res.string.app_proxy_app_list, uiState.selectedPackages.size, uiState.apps.size))
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

                item(key = "bottom_spacer") {
                    Spacer(Modifier.navigationBarsPadding())
                }
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
