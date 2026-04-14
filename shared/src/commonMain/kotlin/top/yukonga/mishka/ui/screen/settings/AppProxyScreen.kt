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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.mishka.platform.AppIcon
import top.yukonga.mishka.ui.component.ListPopupDefaults.MenuPositionProvider
import top.yukonga.mishka.ui.component.SearchBarFake
import top.yukonga.mishka.ui.component.SearchBox
import top.yukonga.mishka.ui.component.SearchPager
import top.yukonga.mishka.ui.component.SearchStatus
import top.yukonga.mishka.viewmodel.AppProxyMode
import top.yukonga.mishka.viewmodel.AppProxyViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
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

    var searchStatus by remember { mutableStateOf(SearchStatus(label = "搜索应用")) }

    // 搜索文本同步到 ViewModel
    val searchText = searchStatus.searchText
    viewModel.setSearchQuery(searchText)

    val filteredApps = remember(searchText, uiState.apps, uiState.showSystemApps, uiState.selectedPackages) {
        viewModel.filteredApps()
    }

    // 更新搜索结果状态
    val resultStatus = when {
        searchText.isEmpty() -> SearchStatus.ResultStatus.DEFAULT
        filteredApps.isEmpty() -> SearchStatus.ResultStatus.EMPTY
        else -> SearchStatus.ResultStatus.SHOW
    }
    if (searchStatus.resultStatus != resultStatus) {
        searchStatus = searchStatus.copy(resultStatus = resultStatus)
    }

    val dynamicTopPadding by remember {
        derivedStateOf { 12.dp * (1f - scrollBehavior.state.collapsedFraction) }
    }

    Scaffold(
        topBar = {
            searchStatus.TopAppBarAnim {
                TopAppBar(
                    title = "应用代理",
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = "返回",
                                tint = MiuixTheme.colorScheme.onSurface,
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
                                contentDescription = "更多",
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
                                    text = "全选",
                                    optionSize = 4,
                                    isSelected = false,
                                    index = 0,
                                    onSelectedIndexChange = {
                                        viewModel.selectAll()
                                        showPopup.value = false
                                    },
                                )
                                DropdownImpl(
                                    text = "全不选",
                                    optionSize = 4,
                                    isSelected = false,
                                    index = 1,
                                    onSelectedIndexChange = {
                                        viewModel.deselectAll()
                                        showPopup.value = false
                                    },
                                )
                                DropdownImpl(
                                    text = "反选",
                                    optionSize = 4,
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
                                    text = if (uiState.showSystemApps) "隐藏系统应用" else "显示系统应用",
                                    optionSize = 4,
                                    isSelected = uiState.showSystemApps,
                                    index = 3,
                                    onSelectedIndexChange = {
                                        viewModel.setShowSystemApps(!uiState.showSystemApps)
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
                                    text = "无匹配应用",
                                    fontSize = 16.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    } else {
                        item(key = "search_results") {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 12.dp),
                            ) {
                                filteredApps.forEach { app ->
                                    AppItem(
                                        appName = app.appName,
                                        packageName = app.packageName,
                                        isSelected = app.packageName in uiState.selectedPackages,
                                        onToggle = { viewModel.toggleApp(app.packageName) },
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(maxOf(bottomPadding, imeBottomPadding)))
                    }
                }
            }
        },
    ) { innerPadding ->
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
                item(key = "mode_title") { SmallTitle(text = "代理模式") }
                item(key = "mode_card") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 6.dp),
                    ) {
                        RadioButtonPreference(
                            title = "允许所有应用",
                            summary = "所有应用的流量都经过代理",
                            selected = uiState.mode == AppProxyMode.AllowAll,
                            onClick = { viewModel.setMode(AppProxyMode.AllowAll) },
                        )
                        RadioButtonPreference(
                            title = "仅允许选中应用",
                            summary = "只有选中的应用经过代理",
                            selected = uiState.mode == AppProxyMode.AllowSelected,
                            onClick = { viewModel.setMode(AppProxyMode.AllowSelected) },
                        )
                        RadioButtonPreference(
                            title = "排除选中应用",
                            summary = "选中的应用不经过代理",
                            selected = uiState.mode == AppProxyMode.DenySelected,
                            onClick = { viewModel.setMode(AppProxyMode.DenySelected) },
                        )
                    }
                }

                // 应用列表
                item(key = "apps_title") {
                    SmallTitle(text = "应用列表 (${uiState.selectedPackages.size}/${uiState.apps.size})")
                }

                if (uiState.isLoading) {
                    item(key = "loading") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "加载应用列表...",
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                } else {
                    item(key = "apps_card") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                        ) {
                            filteredApps.forEach { app ->
                                AppItem(
                                    appName = app.appName,
                                    packageName = app.packageName,
                                    isSelected = app.packageName in uiState.selectedPackages,
                                    onToggle = { viewModel.toggleApp(app.packageName) },
                                )
                            }
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
    BasicComponent(
        title = appName,
        summary = packageName,
        startAction = {
            AppIcon(
                packageName = packageName,
                modifier = Modifier.padding(end = 12.dp),
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
