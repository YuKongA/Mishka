package top.yukonga.mishka.ui.screen.proxy

import androidx.compose.animation.CapturedAnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.mishka.R
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.CardSegment
import top.yukonga.mishka.ui.component.TopLayer
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.LocalCollapsedRailTopObstruction
import top.yukonga.mishka.ui.component.blur.LocalPageBackdrop
import top.yukonga.mishka.ui.component.blur.LocalTopAppBarCenterObstructionStart
import top.yukonga.mishka.ui.component.blur.LocalTopAppBarTitleAlpha
import top.yukonga.mishka.ui.component.blur.LocalTopBarExtraTopInset
import top.yukonga.mishka.ui.component.blur.LocalTopBarStartInset
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.overScrollVertical
import top.yukonga.mishka.ui.platform.IconLoader
import top.yukonga.mishka.ui.theme.LocalContainerSize
import top.yukonga.mishka.ui.theme.LocalPlatformDensity
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.ui.util.DualPaneDividerWidth
import top.yukonga.mishka.ui.util.DualPaneMinWidth
import top.yukonga.mishka.ui.util.LocalHorizontalCutoutSides
import top.yukonga.mishka.ui.util.calculateDualPaneMasterWidth
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.ui.util.rememberHorizontalSafeInsets
import top.yukonga.mishka.viewmodel.ProxyGroupUi
import top.yukonga.mishka.viewmodel.ProxyUiState
import top.yukonga.mishka.viewmodel.ProxyViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassIconDropdownMenu
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.glass.glassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPopupAnchorContent
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.icon.glass.More
import top.yukonga.miuix.kmp.icon.glass.Pin
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.icon.glass.Unpin
import top.yukonga.miuix.kmp.interfaces.HoldDownInteraction
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.grid.items as gridItems
import top.yukonga.miuix.kmp.icon.glass.Sort as GlassSort

private class GroupRowsCacheEntry(
    val all: ImmutableList<String>,
    val delays: ImmutableMap<String, Int>,
    val sortOption: Int,
    val rows: ImmutableList<ImmutableList<String>>,
)

private val SortResIds = listOf(
    R.string.proxy_sort_default,
    R.string.proxy_sort_name,
    R.string.proxy_sort_delay,
)

@Composable
fun ProxyScreen(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    viewModel: ProxyViewModel? = null,
) {
    val uiState = viewModel?.uiState?.collectAsStateWithLifecycle()?.value ?: ProxyUiState()
    val sortOption = viewModel?.sortOption?.collectAsStateWithLifecycle()?.value ?: 0
    val singleColumn = viewModel?.singleColumn?.collectAsStateWithLifecycle()?.value == true
    val showGroupIcons = viewModel?.showGroupIcons?.collectAsStateWithLifecycle()?.value ?: true
    ProxyScreenContent(
        modifier = modifier,
        bottomPadding = bottomPadding,
        uiState = uiState,
        sortOption = sortOption,
        singleColumn = singleColumn,
        showGroupIcons = showGroupIcons,
        onTestNodeDelay = { nodeName -> viewModel?.testNodeDelay(nodeName) },
        onSelectProxy = { groupName, proxyName -> viewModel?.selectProxy(groupName, proxyName) },
        onTestGroupDelay = { groupName -> viewModel?.testGroupDelay(groupName) },
        onUnfixProxy = { groupName -> viewModel?.unfixProxy(groupName) },
        onUpdateSortOption = { viewModel?.updateSortOption(it) },
        onUpdateSingleColumn = { viewModel?.updateSingleColumn(it) },
        onUpdateShowGroupIcons = { viewModel?.updateShowGroupIcons(it) },
    )
}

@Composable
private fun ProxyScreenContent(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    uiState: ProxyUiState = ProxyUiState(),
    sortOption: Int = 0,
    singleColumn: Boolean = false,
    showGroupIcons: Boolean = true,
    onTestNodeDelay: (String) -> Unit = {},
    onSelectProxy: (String, String) -> Unit = { _, _ -> },
    onTestGroupDelay: (String) -> Unit = {},
    onUnfixProxy: (String) -> Unit = {},
    onUpdateSortOption: (Int) -> Unit = {},
    onUpdateSingleColumn: (Boolean) -> Unit = {},
    onUpdateShowGroupIcons: (Boolean) -> Unit = {},
) {
    val singleColumnProgress = animateFloatAsState(
        targetValue = if (singleColumn) 1f else 0f,
        animationSpec = tween(300),
        label = "singleColumnProgress",
    )
    val scrollBehavior = MiuixScrollBehavior()
    val groups = uiState.groups
    val containerSize = LocalContainerSize.current ?: LocalWindowInfo.current.containerSize
    val density = LocalPlatformDensity.current ?: LocalDensity.current
    val maxAnimRows = remember(containerSize.height, density) {
        val windowHeightDp = with(density) { containerSize.height.toDp() }
        // 覆盖 1.8 倍视口高度作为滑动冗余缓冲，防展开中途手势下滑露底
        ((windowHeightDp * 1.8f / 68.dp).toInt()).coerceIn(16, 36)
    }
    val listState = rememberLazyListState()


    val modeHintRes = when (uiState.mode) {
        ProxyViewModel.MODE_DIRECT -> R.string.proxy_mode_direct_hint
        ProxyViewModel.MODE_GLOBAL -> R.string.proxy_mode_global_hint
        else -> null
    }

    val showPopup = remember { mutableStateOf(false) }
    val showSortPopup = remember { mutableStateOf(false) }
    val sortAnchor = rememberGlassPopupAnchor()
    val moreAnchor = rememberGlassPopupAnchor()
    val coroutineScope = rememberCoroutineScope()
    // 节点行动态增删，展开态须存屏幕级可保存列表
    val expandedGroups = rememberSaveable(
        saver = listSaver(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { mutableStateListOf<String>() }
    var selectedGroupName by rememberSaveable { mutableStateOf<String?>(null) }

    // retainedGroups 在收缩动画期间保住 item 防脱落穿插；普通 Map 靠随后状态写入驱动重测
    val expandProgress = remember { mutableMapOf<String, MutableFloatState>() }
    val animatingGroups = remember { mutableStateListOf<String>() }
    val retainedGroups = remember { mutableStateListOf<String>() }
    val expandJobs = remember { mutableMapOf<String, Job>() }

    fun ensureGroupExpanded(name: String) {
        expandJobs[name]?.cancel()
        animatingGroups.remove(name)
        retainedGroups.remove(name)
        expandProgress.getOrPut(name) { mutableFloatStateOf(1f) }.floatValue = 1f
        if (name !in expandedGroups) {
            expandedGroups.add(name)
        }
    }

    fun toggleGroup(name: String) {
        val progress = expandProgress.getOrPut(name) {
            mutableFloatStateOf(if (name in expandedGroups) 1f else 0f)
        }
        val expanding = name !in expandedGroups
        expandJobs[name]?.cancel()
        if (expanding) {
            retainedGroups.remove(name)
            expandedGroups.add(name)
            selectedGroupName = name
        } else {
            if (name !in retainedGroups) retainedGroups.add(name)
            if (selectedGroupName == name) {
                selectedGroupName = expandedGroups.lastOrNull { it != name }
            }
        }
        if (name !in animatingGroups) animatingGroups.add(name)
        expandJobs[name] = coroutineScope.launch {
            animate(
                initialValue = progress.floatValue,
                targetValue = if (expanding) 1f else 0f,
                animationSpec = tween(
                    durationMillis = 280,
                    easing = FastOutSlowInEasing,
                ),
            ) { value, _ ->
                progress.floatValue = value
            }
            // 清理交给接手的新动画
            if (!expanding) {
                expandedGroups.remove(name)
                retainedGroups.remove(name)
            }
            animatingGroups.remove(name)
        }
    }

    // 手势介入滑动时立即收敛动画至终态，释放全量节点
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { isScrolling ->
                if (isScrolling && animatingGroups.isNotEmpty()) {
                    animatingGroups.toList().forEach { name ->
                        val isExpanding = name in expandedGroups
                        expandJobs[name]?.cancel()
                        expandProgress[name]?.floatValue = if (isExpanding) 1f else 0f
                        if (!isExpanding) {
                            expandedGroups.remove(name)
                            retainedGroups.remove(name)
                        }
                        animatingGroups.remove(name)
                    }
                }
            }
    }

    // 分组缓存排序分块，防单节点延迟变动触发全量重排
    val rowsCache = remember { mutableMapOf<String, GroupRowsCacheEntry>() }

    fun getGroupRows(group: ProxyGroupUi, option: Int): ImmutableList<ImmutableList<String>> {
        val cached = rowsCache[group.name]
        if (cached != null && cached.all === group.all && cached.delays === group.delays && cached.sortOption == option) {
            return cached.rows
        }
        val sorted = sortNodes(group.all, group.delays, option)
        val chunked = sorted.chunked(2).map { it.toPersistentList() }.toPersistentList()
        rowsCache[group.name] = GroupRowsCacheEntry(group.all, group.delays, option, chunked)
        return chunked
    }


    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val safeInsets = rememberHorizontalSafeInsets()
        val startInset = LocalTopBarStartInset.current()
        val contentWidth = (maxWidth - startInset - safeInsets.start - safeInsets.end).coerceAtLeast(0.dp)
        val isTablet = contentWidth >= DualPaneMinWidth && groups.isNotEmpty()
        val masterPaneWidth = calculateDualPaneMasterWidth(contentWidth) + startInset + safeInsets.start
        val selectedGroup = selectedGroupName?.let { name -> groups.firstOrNull { it.name == name } }
        LaunchedEffect(isTablet, groups) {
            if (isTablet) {
                if (selectedGroupName == null || groups.none { it.name == selectedGroupName }) {
                    selectedGroupName = expandedGroups.lastOrNull { name -> groups.any { it.name == name } }
                }
            } else {
                val target = selectedGroupName
                if (target != null && groups.any { it.name == target }) {
                    ensureGroupExpanded(target)
                }
            }
        }
        val masterListState = rememberLazyListState()
        val detailGridState = rememberLazyGridState()
        val masterScrollBehavior = MiuixScrollBehavior()
        val detailScrollBehavior = MiuixScrollBehavior()
        LaunchedEffect(selectedGroup?.name) {
            detailGridState.scrollToItem(0)
        }
        val proxyTopBarActions: @Composable () -> Unit = {
            if (groups.isNotEmpty()) {
                GlassIconButton(
                    onClick = { showSortPopup.value = true },
                    modifier = Modifier.glassPopupAnchor(
                        anchor = sortAnchor,
                        cornerRadius = GlassTopAppBarDefaults.ButtonSize / 2,
                    ),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Glass.GlassSort,
                        contentDescription = stringResource(R.string.proxy_sort_title),
                        tint = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .size(28.dp)
                            .glassPopupAnchorContent(sortAnchor),
                    )
                }
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
        }
        val onTestNodeDelayStable = remember(onTestNodeDelay) { onTestNodeDelay }
        val onSelectProxyStable = remember(onSelectProxy) { onSelectProxy }
        if (isTablet) {
            val masterBackdrop = rememberBlurBackdrop()
            val detailBackdrop = rememberBlurBackdrop()
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .width(masterPaneWidth)
                        .fillMaxHeight(),
                ) {
                    CompositionLocalProvider(
                        LocalHorizontalCutoutSides provides WindowInsetsSides.Start,
                    ) {
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            topBar = {
                                BlurredBar(backdrop = masterBackdrop, blurActive = masterBackdrop != null) {
                                    AdaptiveTopAppBar(
                                        title = stringResource(R.string.proxy_title),
                                        scrollBehavior = masterScrollBehavior,
                                    )
                                }
                            },
                        ) { innerPadding ->
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(if (masterBackdrop != null) Modifier.layerBackdrop(masterBackdrop) else Modifier)
                                    .horizontalCutoutPadding()
                                    .scrollEndHaptic()
                                    .overScrollVertical(masterScrollBehavior)
                                    .nestedScroll(masterScrollBehavior.nestedScrollConnection),
                                state = masterListState,
                                contentPadding = PaddingValues(
                                    top = innerPadding.calculateTopPadding() + 12.dp,
                                    end = 12.dp,
                                    bottom = bottomPadding + 12.dp,
                                ),
                            ) {
                                if (modeHintRes != null) {
                                    item(key = "tablet_mode_hint") {
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 12.dp, bottom = 10.dp),
                                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                        ) {
                                            Text(
                                                text = stringResource(modeHintRes),
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                fontSize = 13.sp,
                                            )
                                        }
                                    }
                                }
                                items(groups, key = { "master:${it.name}" }) { group ->
                                    val isSelectedGroup = group.name == selectedGroup?.name
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 12.dp, bottom = 10.dp),
                                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                        colors = if (isSelectedGroup) {
                                            CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)
                                        } else {
                                            CardDefaults.defaultColors()
                                        },
                                        onClick = { selectedGroupName = group.name },
                                        pressFeedbackType = PressFeedbackType.Sink,
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            if (showGroupIcons) {
                                                GroupIcon(icon = group.icon, name = group.name)
                                                Spacer(Modifier.width(12.dp))
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = group.name,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MiuixTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                if (group.now.isNotEmpty()) {
                                                    Text(
                                                        text = group.now,
                                                        fontSize = 12.sp,
                                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                            val nowDelay = group.delays[group.now]
                                            if (nowDelay != null) {
                                                val delayText =
                                                    if (nowDelay < 0) stringResource(R.string.proxy_timeout) else "${nowDelay}ms"
                                                Text(
                                                    text = delayText,
                                                    fontSize = 12.sp,
                                                    color = StatusColors.delay(nowDelay),
                                                )
                                                Spacer(Modifier.width(8.dp))
                                            }
                                            Text(
                                                text = "${group.all.size}",
                                                fontSize = 13.sp,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Image(
                                                imageVector = MiuixIcons.Glass.ChevronForward,
                                                contentDescription = null,
                                                modifier = Modifier.size(15.dp),
                                                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
                                            )
                                        }
                                    }
                                }
                            }
                        }
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
                        .fillMaxHeight(),
                ) {
                    CompositionLocalProvider(
                        LocalTopBarStartInset provides { 0.dp },
                        LocalTopAppBarCenterObstructionStart provides Dp.Unspecified,
                        LocalTopAppBarTitleAlpha provides { 1f },
                        LocalHorizontalCutoutSides provides WindowInsetsSides.End,
                        LocalTopBarExtraTopInset provides LocalCollapsedRailTopObstruction.current,
                    ) {
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            topBar = {
                                BlurredBar(backdrop = detailBackdrop, blurActive = detailBackdrop != null) {
                                    AdaptiveTopAppBar(
                                        title = selectedGroup?.name.orEmpty(),
                                        scrollBehavior = detailScrollBehavior,
                                        actions = { proxyTopBarActions() },
                                    )
                                }
                            },
                        ) { innerPadding ->
                            if (selectedGroup == null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(top = innerPadding.calculateTopPadding(), bottom = bottomPadding),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = stringResource(R.string.proxy_select_group_hint),
                                        fontSize = 15.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            } else {
                                val sortedNodes = remember(selectedGroup.all, selectedGroup.delays, sortOption) {
                                    sortNodes(selectedGroup.all, selectedGroup.delays, sortOption)
                                }
                                val onSelectThisGroup = remember(onSelectProxyStable, selectedGroup.name) {
                                    { proxyName: String -> onSelectProxyStable(selectedGroup.name, proxyName) }
                                }
                                LazyVerticalGrid(
                                    columns = if (singleColumn) GridCells.Fixed(1) else GridCells.Adaptive(minSize = 180.dp),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(if (detailBackdrop != null) Modifier.layerBackdrop(detailBackdrop) else Modifier)
                                        .horizontalCutoutPadding(applySidebarInset = false)
                                        .scrollEndHaptic()
                                        .overScrollVertical(detailScrollBehavior)
                                        .nestedScroll(detailScrollBehavior.nestedScrollConnection),
                                    state = detailGridState,
                                    contentPadding = PaddingValues(
                                        start = 12.dp,
                                        end = 12.dp,
                                        top = innerPadding.calculateTopPadding() + 12.dp,
                                        bottom = bottomPadding + 12.dp,
                                    ),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    item(
                                        key = "detail_header:${selectedGroup.name}",
                                        span = { GridItemSpan(maxLineSpan) },
                                    ) {
                                        val isTestingGroup = selectedGroup.name in uiState.testingGroups
                                        val nowDelay = selectedGroup.delays[selectedGroup.now]
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = 2.dp),
                                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                if (showGroupIcons) {
                                                    GroupIcon(icon = selectedGroup.icon, name = selectedGroup.name)
                                                    Spacer(Modifier.width(12.dp))
                                                }
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = selectedGroup.name,
                                                        fontSize = 17.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MiuixTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    val subtitle = buildString {
                                                        if (selectedGroup.type.isNotEmpty()) append(selectedGroup.type)
                                                        if (selectedGroup.now.isNotEmpty()) {
                                                            if (isNotEmpty()) append(" · ")
                                                            append(selectedGroup.now)
                                                        }
                                                    }
                                                    if (subtitle.isNotEmpty()) {
                                                        Text(
                                                            text = subtitle,
                                                            fontSize = 13.sp,
                                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                    }
                                                }
                                                if (nowDelay != null) {
                                                    val delayText =
                                                        if (nowDelay < 0) stringResource(R.string.proxy_timeout) else "${nowDelay}ms"
                                                    val delayColor = StatusColors.delay(nowDelay)
                                                    Text(
                                                        text = delayText,
                                                        modifier = Modifier
                                                            .squircleBackground(delayColor.copy(alpha = 0.14f), 6.dp)
                                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = delayColor,
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                }
                                                if (selectedGroup.isFixed) {
                                                    IconButton(
                                                        onClick = { onUnfixProxy(selectedGroup.name) },
                                                        modifier = Modifier.size(32.dp),
                                                    ) {
                                                        Icon(
                                                            imageVector = MiuixIcons.Glass.Unpin,
                                                            contentDescription = stringResource(R.string.proxy_unfix),
                                                            modifier = Modifier.size(18.dp),
                                                            tint = StatusColors.warning,
                                                        )
                                                    }
                                                    Spacer(Modifier.width(4.dp))
                                                }
                                                IconButton(
                                                    onClick = { onTestGroupDelay(selectedGroup.name) },
                                                    enabled = !isTestingGroup,
                                                    modifier = Modifier.size(32.dp),
                                                ) {
                                                    if (isTestingGroup) {
                                                        CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp)
                                                    } else {
                                                        Icon(
                                                            imageVector = MiuixIcons.Glass.Refresh,
                                                            contentDescription = stringResource(R.string.proxy_test_group_delay),
                                                            modifier = Modifier.size(18.dp),
                                                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    gridItems(
                                        items = sortedNodes,
                                        key = { "detail_node:${selectedGroup.name}:$it" },
                                        contentType = { "proxy_node_card" },
                                    ) { proxyName ->
                                        ProxyNodeCard(
                                            name = proxyName,
                                            type = selectedGroup.nodeTypes[proxyName] ?: "",
                                            delay = selectedGroup.delays[proxyName],
                                            isSelected = proxyName == selectedGroup.now,
                                            isFixed = proxyName == selectedGroup.fixed,
                                            isSelectable = selectedGroup.isSelectable,
                                            isTesting = proxyName in uiState.testingNodes,
                                            modifier = Modifier.fillMaxWidth(),
                                            defaultContainerColor = MiuixTheme.colorScheme.surfaceContainer,
                                            onTestDelay = onTestNodeDelayStable,
                                            onClick = onSelectThisGroup,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val backdrop = rememberBlurBackdrop()
            val blurActive = backdrop != null
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                        AdaptiveTopAppBar(
                            title = stringResource(R.string.proxy_title),
                            scrollBehavior = scrollBehavior,
                            actions = { proxyTopBarActions() },
                        )
                    }
                },
            ) { innerPadding ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                        .horizontalCutoutPadding()
                        .scrollEndHaptic()
                        .overScrollVertical(scrollBehavior)
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    state = listState,
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                        bottom = bottomPadding,
                    ),
                ) {
                    if (groups.isEmpty()) {
                        item(key = "empty", contentType = "empty") {
                            Column(
                                modifier = Modifier.fillParentMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.proxy_no_groups),
                                    fontSize = 16.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                Text(
                                    text = stringResource(R.string.proxy_start_first),
                                    modifier = Modifier.padding(top = 6.dp),
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    } else {
                        if (modeHintRes != null) {
                            item(key = "mode_hint", contentType = "proxy_mode_hint") {
                                CardSegment(
                                    isFirst = true,
                                    isLast = true,
                                    modifier = Modifier.animateItem(placementSpec = null),
                                    outerTopPadding = 12.dp,
                                    insidePadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                ) {
                                    Text(
                                        text = stringResource(modeHintRes),
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        }


                        // 展平为独立 item，仅组合可见行
                        groups.forEach { group ->
                            val isExpanded = group.name in expandedGroups
                            val rowsPresent = isExpanded || group.name in retainedGroups
                            val isAnimating = group.name in animatingGroups
                            val allRows = if (rowsPresent) {
                                getGroupRows(group, sortOption)
                            } else {
                                persistentListOf()
                            }
                            // 动画期间限制最大渲染行数防卡死，收尾后恢复全量虚拟化
                            val rows = if (isAnimating && allRows.size > maxAnimRows) {
                                allRows.take(maxAnimRows)
                            } else {
                                allRows
                            }
                            val groupProgress = expandProgress[group.name]
                            val currentFraction = groupProgress?.floatValue ?: if (isExpanded) 1f else 0f

                            item(key = "group:${group.name}", contentType = "proxy_group_header") {
                                // 有子行时平接（0dp），完全收起后封底（16dp）
                                val headerBottomCorner = if (currentFraction <= 0.01f) 16.dp else 0.dp

                                CardSegment(
                                    isFirst = true,
                                    isLast = currentFraction <= 0.01f,
                                    modifier = Modifier.animateItem(placementSpec = null),
                                    bottomCornerRadius = headerBottomCorner,
                                    outerTopPadding = 12.dp,
                                ) {
                                    ProxyGroupHeader(
                                        group = group,
                                        showIcon = showGroupIcons,
                                        isExpanded = isExpanded,
                                        expandProgress = groupProgress,
                                        isTesting = group.name in uiState.testingGroups,
                                        onTestDelay = { onTestGroupDelay(group.name) },
                                        onUnfix = { onUnfixProxy(group.name) },
                                        onToggle = { toggleGroup(group.name) },
                                    )
                                }
                            }

                            if (rows.isNotEmpty()) {
                                val lastRowIndex = rows.lastIndex
                                val rowCount = rows.size
                                val groupExpandProgress = expandProgress[group.name]
                                val onSelectThisGroup: (String) -> Unit = { proxyName ->
                                    onSelectProxyStable(group.name, proxyName)
                                }

                                // 动态计算当前露出的最底行，赋予 16dp 底圆角
                                val activeBottomIndex = when {
                                    currentFraction >= 0.99f -> lastRowIndex
                                    currentFraction <= 0.01f -> -1
                                    else -> (rowCount * currentFraction).toInt().coerceIn(0, lastRowIndex)
                                }

                                rows.forEachIndexed { rowIndex, row ->
                                    val isCurrentBottom = rowIndex == activeBottomIndex
                                    val rowBottomCorner = if (isCurrentBottom) 16.dp else 0.dp

                                    item(
                                        key = "nodes:${group.name}:$rowIndex",
                                        contentType = "proxy_node_row",
                                    ) {
                                        CardSegment(
                                            isFirst = false,
                                            isLast = isCurrentBottom,
                                            modifier = Modifier
                                                .animateItem(placementSpec = null)
                                                .clipToBounds(),
                                            bottomCornerRadius = rowBottomCorner,
                                            insidePadding = PaddingValues(
                                                start = 12.dp,
                                                end = 12.dp,
                                            ),
                                        ) {
                                            ProxyNodeRow(
                                                row = row,
                                                group = group,
                                                singleColumnProgress = singleColumnProgress,
                                                expandProgress = groupExpandProgress,
                                                rowIndex = rowIndex,
                                                rowCount = rowCount,
                                                testingNodes = uiState.testingNodes,
                                                onTestNodeDelay = onTestNodeDelayStable,
                                                onSelect = onSelectThisGroup,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        item(key = "bottom_spacer") {
                            Spacer(Modifier.padding(bottom = 12.dp))
                        }
                    }
                }
            }
        }
    }

    if (groups.isNotEmpty()) {
        val pageBackdrop = LocalPageBackdrop.current
        val sortLabels = SortResIds.map { stringResource(it) }
        val reverseLabel = stringResource(R.string.proxy_sort_reverse)
        val singleColumnLabel = stringResource(R.string.proxy_single_column)
        val showGroupIconsLabel = stringResource(R.string.proxy_show_group_icons)
        val currentKey = sortOption / 2
        val isReverse = sortOption % 2 != 0
        val sortEntries = remember(sortOption, sortLabels, reverseLabel) {
            listOf(
                DropdownEntry(
                    items = sortLabels.mapIndexed { index, label ->
                        DropdownItem(
                            text = label,
                            selected = currentKey == index,
                            onClick = { onUpdateSortOption(index * 2 + if (isReverse) 1 else 0) },
                        )
                    },
                ),
                DropdownEntry(
                    items = listOf(
                        DropdownItem(
                            text = reverseLabel,
                            selected = isReverse,
                            onClick = { onUpdateSortOption(currentKey * 2 + if (!isReverse) 1 else 0) },
                        ),
                    ),
                ),
            )
        }
        val moreEntries = remember(singleColumn, showGroupIcons, singleColumnLabel, showGroupIconsLabel) {
            listOf(
                DropdownEntry(
                    items = listOf(
                        DropdownItem(
                            text = singleColumnLabel,
                            selected = singleColumn,
                            onClick = { onUpdateSingleColumn(!singleColumn) },
                        ),
                        DropdownItem(
                            text = showGroupIconsLabel,
                            selected = showGroupIcons,
                            onClick = { onUpdateShowGroupIcons(!showGroupIcons) },
                        ),
                    ),
                ),
            )
        }
        TopLayer {
            GlassIconDropdownMenu(
                show = showSortPopup.value,
                onDismissRequest = { showSortPopup.value = false },
                anchor = sortAnchor,
                anchorContent = {
                    Icon(
                        imageVector = MiuixIcons.Glass.GlassSort,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp),
                    )
                },
                entries = sortEntries,
                backdrop = pageBackdrop,
            )
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
                entries = moreEntries,
                backdrop = pageBackdrop,
            )
        }
    }
}

/**
 * 把 [holdDown] 映射成 [interactionSource] 上的按下态（miuix 的 `HoldDownObserver` 是 internal）。
 * `interactions` 无 replay，故两处不能漏：离开组合补 Release，indication 换实例（深浅色切换）重发。
 */
@Composable
private fun HoldDownEffect(holdDown: Boolean, interactionSource: MutableInteractionSource) {
    val current = remember { mutableStateOf<HoldDownInteraction.HoldDown?>(null) }
    val indication = LocalIndication.current
    LaunchedEffect(holdDown, interactionSource, indication) {
        current.value?.let {
            interactionSource.emit(HoldDownInteraction.Release(it))
            current.value = null
        }
        if (holdDown) {
            val interaction = HoldDownInteraction.HoldDown()
            current.value = interaction
            interactionSource.emit(interaction)
        }
    }
    DisposableEffect(interactionSource) {
        onDispose {
            current.value?.let { interactionSource.tryEmit(HoldDownInteraction.Release(it)) }
            current.value = null
        }
    }
}

@Composable
private fun ProxyGroupHeader(
    group: ProxyGroupUi,
    showIcon: Boolean,
    isExpanded: Boolean,
    expandProgress: FloatState? = null,
    isTesting: Boolean,
    onTestDelay: () -> Unit,
    onUnfix: () -> Unit,
    onToggle: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    HoldDownEffect(isExpanded, interactionSource)
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onToggle()
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CapturedAnimatedVisibility(
            visible = showIcon,
            enter = slideInHorizontally(tween(300)) { -it } +
                    expandHorizontally(tween(300), expandFrom = Alignment.Start),
            exit = slideOutHorizontally(tween(300)) { -it } +
                    shrinkHorizontally(tween(300), shrinkTowards = Alignment.Start),
            label = "groupIconVisibility",
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupIcon(
                    icon = group.icon,
                    name = group.name,
                )
                Spacer(Modifier.width(12.dp))
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (group.now.isNotEmpty()) {
                Text(
                    text = group.now,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val nowDelay = group.delays[group.now]
        if (nowDelay != null) {
            val timeoutText = stringResource(R.string.proxy_timeout)
            val delayText = if (nowDelay < 0) timeoutText else "${nowDelay}ms"
            Text(
                text = delayText,
                fontSize = 12.sp,
                color = StatusColors.delay(nowDelay),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = "${group.all.size}",
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        // 固定是组级属性，放组头才能在收起态下操作
        if (group.isFixed) {
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onUnfix,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.Glass.Unpin,
                    contentDescription = stringResource(R.string.proxy_unfix),
                    modifier = Modifier.size(16.dp),
                    tint = StatusColors.warning,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = onTestDelay,
            enabled = !isTesting,
            modifier = Modifier.size(24.dp),
        ) {
            if (isTesting) {
                CircularProgressIndicator(
                    size = 14.dp,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = MiuixIcons.Glass.Refresh,
                    contentDescription = stringResource(R.string.proxy_test_group_delay),
                    modifier = Modifier.size(16.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        val layoutDirection = LocalLayoutDirection.current
        Image(
            imageVector = MiuixIcons.Glass.ChevronForward,
            contentDescription = null,
            modifier = Modifier
                .size(15.dp)
                .graphicsLayer {
                    scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                    val p = expandProgress?.floatValue ?: if (isExpanded) 1f else 0f
                    rotationZ = lerp(0f, 90f, p)
                },
            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
        )
    }
}

@Composable
private fun GroupIcon(
    icon: String,
    name: String,
) {
    if (icon.isNotEmpty()) {
        val iconVersion by IconLoader.iconVersion.collectAsStateWithLifecycle()
        var bitmap by remember(icon) { mutableStateOf<ImageBitmap?>(null) }

        LaunchedEffect(icon, iconVersion) {
            if (bitmap == null) {
                bitmap = IconLoader.loadIcon(icon)
            }
        }
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = name,
                modifier = Modifier
                    .size(30.dp)
                    .squircleClip(8.dp),
            )
        } else {
            DefaultGroupIcon(name)
        }
    } else {
        DefaultGroupIcon(name)
    }
}

@Composable
private fun DefaultGroupIcon(name: String) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .squircleBackground(MiuixTheme.colorScheme.primary.copy(alpha = 0.1f), 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (name.isEmpty()) "" else {
                val first = name[0]
                if (first.isHighSurrogate() && name.length > 1 && name[1].isLowSurrogate()) {
                    name.substring(0, 2)
                } else {
                    first.toString()
                }
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.primary.copy(0.8f),
        )
    }
}

private val NodeRowHorizontalGap = 8.dp
private val NodeRowVerticalGap = 12.dp
private val NodeRowBottomPadding = 12.dp

// 顶底距由行内 Layout 承担，否则高度收缩后残留段底色
private val NodeRowTopPadding = 12.dp

// 恒定分行，单双列由 Layout 连续插值，保证同 item 内平滑过渡
@Composable
private fun ProxyNodeRow(
    row: ImmutableList<String>,
    group: ProxyGroupUi,
    singleColumnProgress: State<Float>,
    expandProgress: FloatState?,
    rowIndex: Int,
    rowCount: Int,
    testingNodes: ImmutableSet<String> = persistentSetOf(),
    onTestNodeDelay: (String) -> Unit = {},
    onSelect: (String) -> Unit,
) {
    Layout(
        content = {
            row.forEach { proxyName ->
                val isSelected = proxyName == group.now
                val delay = group.delays[proxyName]
                val nodeType = group.nodeTypes[proxyName] ?: ""

                ProxyNodeCard(
                    name = proxyName,
                    type = nodeType,
                    delay = delay,
                    isSelected = isSelected,
                    isFixed = proxyName == group.fixed,
                    isSelectable = group.isSelectable,
                    isTesting = proxyName in testingNodes,
                    onTestDelay = onTestNodeDelay,
                    onClick = onSelect,
                )
            }
        },
    ) { measurables, constraints ->
        // measure 阶段读进度，动画帧只重测不重组
        val fraction = singleColumnProgress.value
        val rowWidth = constraints.maxWidth
        val horizontalGap = NodeRowHorizontalGap.roundToPx()
        val verticalGap = NodeRowVerticalGap.roundToPx()
        val halfWidth = (rowWidth - horizontalGap) / 2
        val cellWidth = lerp(halfWidth, rowWidth, fraction)

        val cellConstraints = constraints.copy(minWidth = cellWidth, maxWidth = cellWidth)
        val first = if (measurables.isNotEmpty()) measurables[0].measure(cellConstraints) else null
        val second = if (measurables.size > 1) measurables[1].measure(cellConstraints) else null

        // 竖排基准取首项实际高度，两节点不等高时不留多余空隙
        val secondX = lerp(halfWidth + horizontalGap, 0, fraction)
        val secondY = lerp(0, (first?.height ?: 0) + verticalGap, fraction)
        val topPadding = if (rowIndex == 0) NodeRowTopPadding.roundToPx() else 0
        val contentHeight = topPadding + maxOf(
            first?.height ?: 0,
            if (second != null) secondY + second.height else 0,
        ) + NodeRowBottomPadding.roundToPx()

        val expand = expandProgress?.floatValue ?: 1f
        val visibleFraction = if (expand >= 1f) {
            1f
        } else if (expand <= 0f) {
            0f
        } else {
            val targetRows = rowCount * expand
            (targetRows - rowIndex.toFloat()).coerceIn(0f, 1f)
        }
        val rowHeight = (contentHeight * visibleFraction).roundToInt()

        // 满高测量顶对齐，超出部分由段裁切（卷起而非变形）
        layout(rowWidth, rowHeight) {
            if (visibleFraction > 0f) {
                first?.placeRelative(0, topPadding)
                second?.placeRelative(secondX, secondY + topPadding)
            }
        }
    }
}

@Composable
private fun ProxyNodeCard(
    name: String,
    type: String,
    delay: Int?,
    isSelected: Boolean,
    isFixed: Boolean,
    isSelectable: Boolean,
    isTesting: Boolean = false,
    modifier: Modifier = Modifier,
    defaultContainerColor: Color = MiuixTheme.colorScheme.surface,
    onTestDelay: (String) -> Unit = {},
    onClick: (String) -> Unit,
) {
    val timeoutStr = stringResource(R.string.proxy_timeout)
    val delayText = when {
        delay == null -> null
        delay < 0 -> timeoutStr
        else -> "$delay"
    }
    val delayColor = StatusColors.delay(delay)
    val testNodeDelayLabel = stringResource(R.string.proxy_test_node_delay)

    // 固定与选中异色，提示该组不再自动切换
    val backgroundColor = when {
        isFixed -> StatusColors.fixedNodeContainer
        isSelected -> StatusColors.selectedNodeContainer
        else -> defaultContainerColor
    }

    Box(
        modifier = modifier
            .then(
                if (isSelectable) {
                    Modifier
                        .squircleSurface(color = backgroundColor, cornerRadius = 12.dp)
                        .clickable { onClick(name) }
                } else {
                    Modifier.squircleBackground(color = backgroundColor, cornerRadius = 12.dp)
                }
            )
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isFixed) {
                        Icon(
                            imageVector = MiuixIcons.Glass.Pin,
                            contentDescription = stringResource(R.string.proxy_fixed),
                            modifier = Modifier.size(11.dp),
                            tint = StatusColors.warning,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    // 仅选中项开跑马灯，其余省略号截断
                    Text(
                        text = name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                        overflow = if (isSelected) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .then(
                                if (isSelected) {
                                    Modifier.basicMarquee(
                                        iterations = Int.MAX_VALUE,
                                        initialDelayMillis = 1500,
                                    )
                                } else {
                                    Modifier
                                }
                            ),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 28.dp)
                        .clickable(
                            enabled = !isTesting,
                            interactionSource = null,
                            indication = null,
                            onClickLabel = testNodeDelayLabel,
                        ) { onTestDelay(name) },
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    // 三态互斥，行高由节点名主导防抖动
                    when {
                        isTesting -> CircularProgressIndicator(
                            size = 12.dp,
                            strokeWidth = 2.dp,
                        )

                        delayText != null -> Text(
                            text = delayText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = delayColor,
                        )

                        else -> Icon(
                            imageVector = MiuixIcons.Glass.Refresh,
                            contentDescription = testNodeDelayLabel,
                            modifier = Modifier.size(14.dp),
                            tint = StatusColors.neutral,
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            if (type.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .squircleBackground(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f), 3.dp)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = type,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

// 延迟排序超时与未测沉底，倒序仅翻转有效部分
private fun sortNodes(
    names: List<String>,
    delays: Map<String, Int>,
    sortOption: Int,
): List<String> {
    val key = sortOption / 2
    val reverse = sortOption % 2 != 0
    return when (key) {
        1 -> {
            val comparator = if (reverse) String.CASE_INSENSITIVE_ORDER.reversed() else String.CASE_INSENSITIVE_ORDER
            names.sortedWith(comparator)
        }

        2 -> {
            val valid = ArrayList<String>(names.size)
            val invalid = ArrayList<String>()
            for (name in names) {
                val d = delays[name]
                if (d != null && d > 0) valid.add(name) else invalid.add(name)
            }
            if (reverse) {
                valid.sortByDescending { delays[it] ?: Int.MAX_VALUE }
            } else {
                valid.sortBy { delays[it] ?: Int.MAX_VALUE }
            }
            valid.addAll(invalid)
            valid
        }

        else -> if (reverse) names.reversed() else names
    }
}

private fun sampleProxyUiState(): ProxyUiState = ProxyUiState(
    mode = "rule",
    groups = persistentListOf(
        ProxyGroupUi(
            name = "Auto",
            type = "URLTest",
            now = "HK-01",
            all = persistentListOf("HK-01", "HK-02", "JP-01", "SG-01", "US-01"),
            delays = persistentMapOf("HK-01" to 55, "HK-02" to 68, "JP-01" to 102, "SG-01" to 88, "US-01" to 195),
            nodeTypes = persistentMapOf(
                "HK-01" to "Shadowsocks",
                "HK-02" to "Shadowsocks",
                "JP-01" to "VMess",
                "SG-01" to "Trojan",
                "US-01" to "Vless"
            ),
        ),
        ProxyGroupUi(
            name = "AI",
            type = "Selector",
            now = "JP-01",
            all = persistentListOf("JP-01", "SG-01", "US-01"),
            delays = persistentMapOf("JP-01" to 102, "SG-01" to 88, "US-01" to 195),
        ),
        ProxyGroupUi(
            name = "YouTube",
            type = "Selector",
            now = "Auto",
            all = persistentListOf("Auto", "HK-01", "JP-01", "SG-01"),
            delays = persistentMapOf("Auto" to 55, "HK-01" to 55, "JP-01" to 102, "SG-01" to 88),
        ),
        ProxyGroupUi(
            name = "Google",
            type = "Selector",
            now = "Auto",
            all = persistentListOf("Auto", "HK-01", "SG-01"),
            delays = persistentMapOf("Auto" to 55, "HK-01" to 55, "SG-01" to 88),
        ),
        ProxyGroupUi(
            name = "GitHub",
            type = "Selector",
            now = "Auto",
            all = persistentListOf("Auto", "JP-01", "US-01"),
            delays = persistentMapOf("Auto" to 55, "JP-01" to 102, "US-01" to 195),
        ),
    ),
)

@Preview(showBackground = true)
@Composable
private fun ProxyScreenPreview() {
    MiuixTheme {
        ProxyScreenContent(uiState = sampleProxyUiState())
    }
}

@Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
private fun ProxyScreenTabletPreview() {
    MiuixTheme {
        ProxyScreenContent(uiState = sampleProxyUiState())
    }
}
