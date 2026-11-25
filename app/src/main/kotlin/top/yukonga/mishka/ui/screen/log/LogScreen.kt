package top.yukonga.mishka.ui.screen.log

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.computedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import top.yukonga.mishka.R
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.BackNavigationIcon
import top.yukonga.mishka.ui.component.GlassDialogTextButton
import top.yukonga.mishka.ui.component.TopBarIconButton
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.LocalBlurEnabled
import top.yukonga.mishka.ui.component.blur.LocalTopBarExtraTopInset
import top.yukonga.mishka.ui.component.blur.LocalPageBackdrop
import top.yukonga.mishka.ui.component.blur.PageBackdropBox
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.overScrollVertical
import top.yukonga.mishka.ui.platform.setPlainText
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.util.logLevelTag
import top.yukonga.mishka.viewmodel.IndexedLog
import top.yukonga.mishka.viewmodel.LogViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassSearchOverlay
import top.yukonga.miuix.kmp.glass.GlassSearchOverlayDefaults
import top.yukonga.miuix.kmp.glass.glassSearchBarTransition
import top.yukonga.miuix.kmp.glass.rememberGlassSearchBarAlpha
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.icon.glass.Delete as GlassDelete
import top.yukonga.miuix.kmp.icon.glass.Search as GlassSearch

@Composable
fun LogScreen(
    viewModel: LogViewModel,
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()
    var searchExpanded by remember { mutableStateOf(false) }
    var selectedLog by remember { mutableStateOf<IndexedLog?>(null) }

    DisposableEffect(viewModel) {
        viewModel.connect()
        onDispose { viewModel.disconnect() }
    }

    val barAlpha = rememberGlassSearchBarAlpha(searchExpanded)

    // 仅当用户已滚到底部时自动跟随新日志，否则不抢夺滚动位置
    val autoScrollEnabled by remember {
        computedStateOf {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) true
            else {
                val lastVisible = visible.last().index
                // bottom_spacer 或最后一条 log 在可视范围内即视为"在底部"
                lastVisible >= info.totalItemsCount - 2
            }
        }
    }

    val contentUnderTopBar by remember(listState) {
        computedStateOf { listState.canScrollBackward }
    }

    // key 用单调递增的末条 id：缓冲写满后 size 恒定，不能再以 size 变化驱动自动跟随
    val lastLogId = logs.lastOrNull()?.id
    LaunchedEffect(lastLogId) {
        if (lastLogId == null) {
            scrollBehavior.state.heightOffset = 0f
            scrollBehavior.state.contentOffset = 0f
            return@LaunchedEffect
        }
        // 搜索态下主列表被弹出层盖住，滚它也看不见，收起后还会停在错位置；
        // 索引包含 top_spacer (0) 与末尾 bottom_spacer (logs.size + 1)
        if (autoScrollEnabled && !searchExpanded && selectedLog == null) {
            listState.animateScrollToItem(logs.size + 1)
        }
    }

    // animateScrollToItem 不经过 NestedScrollConnection，导致 heightOffset 停在 0、
    // GlassTopAppBar 的渐进模糊带与大标题折叠不触发；单独跟踪列表回滚态补齐折叠
    LaunchedEffect(contentUnderTopBar, autoScrollEnabled, searchExpanded, selectedLog) {
        val scrollState = scrollBehavior.state
        if (contentUnderTopBar &&
            autoScrollEnabled &&
            !searchExpanded &&
            selectedLog == null &&
            scrollState.heightOffsetLimit < 0f &&
            scrollState.heightOffset > scrollState.heightOffsetLimit
        ) {
            animate(
                initialValue = scrollState.heightOffset,
                targetValue = scrollState.heightOffsetLimit,
                animationSpec = scrollBehavior.snapAnimationSpec ?: spring(),
            ) { value, _ ->
                scrollState.heightOffset = value
            }
        }
    }
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val blurEnabled = LocalBlurEnabled.current
    val searchListState = rememberLazyListState()

    PageBackdropBox(
        modifier = Modifier.background(MiuixTheme.colorScheme.surface),
    ) {
        Scaffold(
            modifier = Modifier
                .graphicsLayer { alpha = barAlpha.value }
                .then(if (searchExpanded) Modifier.clearAndSetSemantics { } else Modifier),
            topBar = {
                BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                    AdaptiveTopAppBar(
                        title = stringResource(R.string.log_title),
                        contentModifier = Modifier.glassSearchBarTransition(
                            expanded = searchExpanded,
                            blurEnabled = blurEnabled,
                        ) { 1f },
                        scrollBehavior = scrollBehavior,
                        isContentScrolled = contentUnderTopBar,
                        navigationIcon = { BackNavigationIcon(onBack) },
                        actions = {
                            TopBarIconButton(
                                onClick = { searchExpanded = true },
                                glassIcon = MiuixIcons.Glass.GlassSearch,
                                contentDescription = stringResource(R.string.log_search),
                            )
                            TopBarIconButton(
                                onClick = { viewModel.clearLogs() },
                                glassIcon = MiuixIcons.Glass.GlassDelete,
                                contentDescription = stringResource(R.string.log_clear),
                            )
                        },
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
                overscrollEffect = null,
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                ),
            ) {
                if (logs.isEmpty()) {
                    item(key = "empty", contentType = "empty") {
                        Column(
                            modifier = Modifier
                                .fillParentMaxSize()
                                .padding(horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            when {
                                uiState.isLogDisabled -> {
                                    Text(
                                        text = stringResource(R.string.log_disabled),
                                        fontSize = 16.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        textAlign = TextAlign.Center,
                                    )
                                    Text(
                                        text = stringResource(R.string.log_disabled_hint),
                                        modifier = Modifier.padding(top = 6.dp),
                                        fontSize = 14.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        textAlign = TextAlign.Center,
                                    )
                                }

                                !uiState.isConnected -> {
                                    Text(
                                        text = stringResource(R.string.log_not_connected),
                                        fontSize = 16.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        textAlign = TextAlign.Center,
                                    )
                                }

                                uiState.isConfigLoaded -> {
                                    Text(
                                        text = stringResource(R.string.log_waiting),
                                        fontSize = 16.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    item(key = "top_spacer", contentType = "spacer") {
                        Spacer(Modifier.height(12.dp))
                    }
                }

                items(
                    items = logs,
                    key = { it.id },
                    contentType = { "log" },
                ) { indexedLog ->
                    LogCard(
                        entry = indexedLog,
                        onClick = { selectedLog = indexedLog },
                    )
                }

                item(key = "bottom_spacer", contentType = "spacer") {
                    Spacer(
                        Modifier
                            .height(24.dp)
                            .navigationBarsPadding(),
                    )
                }
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
            label = stringResource(R.string.log_search),
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
            val filteredLogs = remember(query, logs) {
                viewModel.filteredLogs(query)
            }
            LaunchedEffect(query) {
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

                if (filteredLogs.isEmpty()) {
                    item(key = "search_empty") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.log_no_match),
                                fontSize = 16.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                } else {
                    items(
                        items = filteredLogs,
                        key = { it.id },
                        contentType = { "log" },
                    ) { indexedLog ->
                        LogCard(
                            entry = indexedLog,
                            onClick = { selectedLog = indexedLog },
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

        LogDetailDialog(
            entry = selectedLog,
            onDismiss = { selectedLog = null },
        )
    }
}

@Composable
private fun LogCard(
    entry: IndexedLog,
    onClick: () -> Unit,
) {
    val log = entry.message
    val parsed = entry.parsed
    val levelInfo = logLevelInfo(log.type)
    val isParsed = parsed.target.isNotEmpty()
    val layoutDirection = LocalLayoutDirection.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        onClick = onClick,
        showIndication = true,
        insideMargin = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (isParsed) {
                    Text(
                        text = parsed.target,
                        modifier = Modifier.basicMarquee(),
                        fontWeight = FontWeight(550),
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                    )

                    if (parsed.rule.isNotEmpty()) {
                        Text(
                            text = parsed.rule,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (parsed.proxy.isNotEmpty()) {
                        Text(
                            text = parsed.proxy,
                            modifier = Modifier.basicMarquee(),
                            fontSize = 12.sp,
                            fontWeight = FontWeight(550),
                            color = levelInfo.color,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                } else {
                    Text(
                        text = parsed.body.ifEmpty { log.payload },
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    LogTag(
                        label = levelInfo.tag,
                        backgroundColor = levelInfo.color.copy(alpha = 0.14f),
                        contentColor = levelInfo.color,
                    )
                    if (parsed.protocol.isNotEmpty()) {
                        LogTag(
                            label = parsed.protocol,
                            backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
                            contentColor = MiuixTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            Icon(
                imageVector = MiuixIcons.Glass.ChevronForward,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(18.dp)
                    .graphicsLayer {
                        if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                    },
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }
}

@Composable
private fun LogTag(
    label: String,
    backgroundColor: Color,
    contentColor: Color,
) {
    Box(
        modifier = Modifier.squircleBackground(backgroundColor, 6.dp),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            text = label,
            color = contentColor,
            fontSize = 9.sp,
            fontWeight = FontWeight(750),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun LogDetailDialog(
    entry: IndexedLog?,
    onDismiss: () -> Unit,
) {
    // 关闭动画期间保留最后一条日志，避免弹窗淡出途中内容闪空
    var lastEntry by remember { mutableStateOf(entry) }
    if (entry != null) lastEntry = entry
    val displayEntry = lastEntry ?: return
    val displayLog = displayEntry.message
    val parsed = displayEntry.parsed
    val levelInfo = logLevelInfo(displayLog.type)
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val copiedMsg = stringResource(R.string.common_copied)

    val levelLabel = stringResource(R.string.log_detail_level)
    val protocolLabel = stringResource(R.string.log_detail_protocol)
    val sourceLabel = stringResource(R.string.log_detail_source)
    val targetLabel = stringResource(R.string.log_detail_target)
    val ruleLabel = stringResource(R.string.log_detail_rule)
    val proxyLabel = stringResource(R.string.log_detail_proxy)
    val rawLabel = stringResource(R.string.log_detail_raw)

    val detailText = remember(
        displayLog,
        parsed,
        levelInfo.tag,
        levelLabel,
        protocolLabel,
        sourceLabel,
        targetLabel,
        ruleLabel,
        proxyLabel,
        rawLabel,
    ) {
        val fields = buildList {
            add(levelLabel to levelInfo.tag)
            if (parsed.protocol.isNotEmpty()) add(protocolLabel to parsed.protocol)
            if (parsed.source.isNotEmpty()) add(sourceLabel to parsed.source)
            if (parsed.target.isNotEmpty()) add(targetLabel to parsed.target)
            if (parsed.rule.isNotEmpty()) add(ruleLabel to parsed.rule)
            if (parsed.proxy.isNotEmpty()) add(proxyLabel to parsed.proxy)
            add(rawLabel to displayLog.payload.trim())
        }
        buildAnnotatedString {
            fields.forEachIndexed { index, (key, value) ->
                if (index > 0) append('\n')
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append("$key: ")
                }
                append(value)
            }
        }
    }

    GlassDialog(
        show = entry != null,
        backdrop = LocalPageBackdrop.current,
        title = parsed.target.ifEmpty { stringResource(R.string.log_detail_title) },
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp),
        ) {
            SelectionContainer(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = detailText,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlassDialogTextButton(
                    text = stringResource(R.string.common_copy),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        clipboardScope.launch { clipboard.setPlainText(displayLog.payload.trim()) }
                        showToast(copiedMsg)
                    },
                )
                GlassDialogTextButton(
                    text = stringResource(R.string.common_close),
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Primary,
                    onClick = onDismiss,
                )
            }
        }
    }
}

@Immutable
private data class LevelInfo(val tag: String, val color: Color)

@Composable
@ReadOnlyComposable
private fun logLevelInfo(type: String): LevelInfo {
    val tag = logLevelTag(type)
    val color = when (type.lowercase()) {
        "error" -> StatusColors.danger
        "warning" -> StatusColors.warning
        "info" -> StatusColors.info
        "debug" -> StatusColors.healthy
        else -> StatusColors.neutral
    }
    return LevelInfo(tag, color)
}
