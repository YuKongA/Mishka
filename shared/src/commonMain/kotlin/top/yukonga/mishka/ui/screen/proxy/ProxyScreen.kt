package top.yukonga.mishka.ui.screen.proxy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.yukonga.mishka.viewmodel.ProxyGroupUi
import top.yukonga.mishka.viewmodel.ProxyUiState
import top.yukonga.mishka.viewmodel.ProxyViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ProxyScreen(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    viewModel: ProxyViewModel? = null,
) {
    val uiState = viewModel?.uiState?.collectAsState()?.value ?: ProxyUiState()
    val scrollBehavior = MiuixScrollBehavior()
    val coroutineScope = rememberCoroutineScope()

    val dynamicTopPadding by remember {
        derivedStateOf { 12.dp * (1f - scrollBehavior.state.collapsedFraction) }
    }

    val groups = uiState.groups
    val tabNames = remember(groups) { groups.map { it.name } }

    val pagerState = rememberPagerState(pageCount = { groups.size.coerceAtLeast(1) })
    val tabRowHeight by remember { mutableStateOf(40.dp) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = "代理",
                scrollBehavior = scrollBehavior,
                actions = {
                    if (groups.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                val currentGroup = groups.getOrNull(pagerState.currentPage)
                                if (currentGroup != null) {
                                    viewModel?.testGroupDelay(currentGroup.name)
                                }
                            },
                            enabled = !uiState.isTesting,
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = "测速",
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                },
                bottomContent = {
                    if (tabNames.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(top = dynamicTopPadding, bottom = 6.dp),
                        ) {
                            TabRow(
                                tabs = tabNames,
                                selectedTabIndex = pagerState.currentPage.coerceIn(0, tabNames.lastIndex.coerceAtLeast(0)),
                                onTabSelected = { index ->
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(index)
                                    }
                                },
                                minWidth = 120.dp,
                                maxWidth = 200.dp,
                                height = tabRowHeight,
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (groups.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding(), bottom = bottomPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "暂无代理组",
                    fontSize = 16.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = "请先启动代理服务",
                    modifier = Modifier.padding(top = 6.dp),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val group = groups.getOrNull(page) ?: return@HorizontalPager
                ProxyGroupPage(
                    group = group,
                    topPadding = innerPadding.calculateTopPadding(),
                    bottomPadding = bottomPadding,
                    scrollBehavior = scrollBehavior,
                    onSelect = { proxyName ->
                        if (group.type.lowercase() == "selector") {
                            viewModel?.selectProxy(group.name, proxyName)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ProxyGroupPage(
    group: ProxyGroupUi,
    topPadding: Dp,
    bottomPadding: Dp,
    scrollBehavior: ScrollBehavior,
    onSelect: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = PaddingValues(
            top = topPadding,
            bottom = bottomPadding,
        ),
    ) {
        if (group.all.isEmpty()) {
            item(key = "empty") {
                Column(
                    modifier = Modifier.fillParentMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "该组暂无节点",
                        fontSize = 16.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        } else {
            item(key = "nodes") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = 6.dp),
                ) {
                    group.all.forEach { proxyName ->
                        val isSelected = proxyName == group.now
                        val delay = group.delays[proxyName]
                        val isSelectable = group.type.lowercase() == "selector"

                        ProxyNodeItem(
                            name = proxyName,
                            isSelected = isSelected,
                            delay = delay,
                            isSelectable = isSelectable,
                            onClick = { onSelect(proxyName) },
                        )
                    }
                }
            }

            item(key = "bottom_spacer") {
                androidx.compose.foundation.layout.Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun ProxyNodeItem(
    name: String,
    isSelected: Boolean,
    delay: Int?,
    isSelectable: Boolean,
    onClick: () -> Unit,
) {
    val delayText = when {
        delay == null -> null
        delay < 0 -> "超时"
        else -> "${delay}ms"
    }
    val delayColor = when {
        delay == null -> Color(0xFF9E9E9E)
        delay < 0 -> Color(0xFFE53935)
        delay < 200 -> Color(0xFF4CAF50)
        delay < 500 -> Color(0xFFFFC107)
        else -> Color(0xFFE53935)
    }

    BasicComponent(
        title = name,
        titleColor = if (isSelected) {
            BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.primary)
        } else {
            BasicComponentDefaults.titleColor()
        },
        startAction = if (isSelected) {
            {
                Box(
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.primary),
                )
            }
        } else null,
        endActions = if (delayText != null) {
            {
                Text(
                    text = delayText,
                    fontSize = 13.sp,
                    color = delayColor,
                )
            }
        } else null,
        onClick = if (isSelectable) onClick else null,
    )
}
