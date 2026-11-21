package top.yukonga.mishka.ui.screen.log

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.computedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.mishka.R
import top.yukonga.mishka.domain.model.LogMessage
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.BackNavigationIcon
import top.yukonga.mishka.ui.component.GlassSearchOverlay
import top.yukonga.mishka.ui.component.TopBarIconButton
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.glassSearchBarTransition
import top.yukonga.mishka.ui.component.overScrollVertical
import top.yukonga.mishka.ui.component.rememberGlassSearchBarAlpha
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.viewmodel.LogViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
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

    DisposableEffect(Unit) {
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

    // key 用单调递增的末条 id：缓冲写满后 size 恒定，不能再以 size 变化驱动自动跟随
    val lastLogId = logs.lastOrNull()?.id
    LaunchedEffect(lastLogId) {
        // 搜索态下主列表被弹出层盖住，滚它也看不见，收起后还会停在错位置
        if (lastLogId != null && autoScrollEnabled && !searchExpanded) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
    ) {
        Scaffold(
            modifier = Modifier.glassSearchBarTransition(searchExpanded) { barAlpha.value },
            topBar = {
                BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                    AdaptiveTopAppBar(
                        title = stringResource(R.string.log_title),
                        scrollBehavior = scrollBehavior,
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
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                ),
            ) {
                if (logs.isEmpty()) {
                    item(key = "empty", contentType = "empty") {
                        Column(
                            modifier = Modifier.fillParentMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = if (uiState.isConnected) {
                                    stringResource(R.string.log_waiting)
                                } else {
                                    stringResource(R.string.log_not_connected)
                                },
                                fontSize = 16.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
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
                    LogCard(indexedLog.message)
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

        GlassSearchOverlay(
            expanded = searchExpanded,
            onDismissRequest = { searchExpanded = false },
            label = stringResource(R.string.log_search),
        ) { query ->
            val filteredLogs = remember(query, logs) {
                viewModel.filteredLogs(query)
            }
            val imeBottomPadding = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .overScrollVertical(),
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
                        LogCard(indexedLog.message)
                    }
                }

                item {
                    Spacer(
                        Modifier
                            .height(imeBottomPadding)
                            .navigationBarsPadding(),
                    )
                }
            }
        }
    }
}

/**
 * 解析后的日志条目
 */
private data class ParsedLog(
    val protocol: String = "",
    val source: String = "",
    val target: String = "",
    val rule: String = "",
    val proxy: String = "",
    val raw: String = "",
)

/**
 * 解析 mihomo 日志 payload
 * 格式: [TCP] 198.18.0.1:45552 --> statusapi.micloud.xiaomi.com:443 match GeoSite(cn) using 全球直连(DIRECT)
 */
private fun parsePayload(payload: String): ParsedLog {
    val raw = payload.trim()

    // 提取协议 [TCP] / [UDP]
    val protocolMatch = Regex("^\\[(\\w+)]").find(raw)
    val protocol = protocolMatch?.groupValues?.get(1) ?: ""
    val rest = if (protocolMatch != null) raw.substring(protocolMatch.range.last + 1).trim() else raw

    // 提取 source --> target
    val arrowIdx = rest.indexOf("-->")
    if (arrowIdx < 0) return ParsedLog(raw = raw)

    val source = rest.substring(0, arrowIdx).trim()
    val afterArrow = rest.substring(arrowIdx + 3).trim()

    // 提取 match ... using ...
    val matchIdx = afterArrow.indexOf(" match ")
    if (matchIdx < 0) {
        return ParsedLog(protocol = protocol, source = source, target = afterArrow, raw = raw)
    }

    val target = afterArrow.substring(0, matchIdx).trim()
    val matchPart = afterArrow.substring(matchIdx + 7).trim()

    val usingIdx = matchPart.indexOf(" using ")
    val rule: String
    val proxy: String
    if (usingIdx >= 0) {
        rule = matchPart.substring(0, usingIdx).trim()
        proxy = matchPart.substring(usingIdx + 7).trim()
    } else {
        rule = matchPart
        proxy = ""
    }

    return ParsedLog(protocol = protocol, source = source, target = target, rule = rule, proxy = proxy, raw = raw)
}

@Composable
private fun LogCard(log: LogMessage) {
    val levelInfo = getLevelInfo(log.type)
    val parsed = remember(log.payload) { parsePayload(log.payload) }
    val isParsed = parsed.target.isNotEmpty()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(12.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 顶行：级别标签 + 协议
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LevelBadge(levelInfo)

                Spacer(Modifier.width(8.dp))

                if (isParsed && parsed.protocol.isNotEmpty()) {
                    ProtocolBadge(parsed.protocol)
                    Spacer(Modifier.width(8.dp))
                }

                Text(
                    text = levelInfo.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = levelInfo.color,
                )
            }

            if (isParsed) {
                // 目标地址
                Text(
                    text = parsed.target,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                )

                // 规则 + 代理
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (parsed.rule.isNotEmpty()) {
                        Text(
                            text = parsed.rule,
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    if (parsed.proxy.isNotEmpty()) {
                        Text(
                            text = parsed.proxy,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = levelInfo.color.copy(alpha = 0.8f),
                        )
                    }
                }
            } else {
                // 无法解析，原样显示
                Text(
                    text = log.payload,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun LevelBadge(levelInfo: LevelInfo) {
    Box(
        modifier = Modifier
            .size(width = 20.dp, height = 16.dp)
            .squircleBackground(levelInfo.color, 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = levelInfo.label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = Color.White,
        )
    }
}

@Composable
private fun ProtocolBadge(protocol: String) {
    Box(
        modifier = Modifier
            .squircleBackground(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f), 3.dp)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = protocol,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

private data class LevelInfo(val label: String, val name: String, val color: Color)

private fun getLevelInfo(type: String): LevelInfo {
    return when (type.lowercase()) {
        "error" -> LevelInfo("E", "Error", Color(0xFFFF5252))
        "warning" -> LevelInfo("W", "Warning", Color(0xFFFFB74D))
        "info" -> LevelInfo("I", "Info", Color(0xFF69C0FF))
        "debug" -> LevelInfo("D", "Debug", Color(0xFF81C784))
        else -> LevelInfo("V", "Verbose", Color(0xFFBDBDBD))
    }
}
