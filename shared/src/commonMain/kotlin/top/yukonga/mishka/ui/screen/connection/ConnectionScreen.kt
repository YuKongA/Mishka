package top.yukonga.mishka.ui.screen.connection

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.common_back
import mishka.shared.generated.resources.common_cancel
import mishka.shared.generated.resources.common_close
import mishka.shared.generated.resources.common_confirm
import mishka.shared.generated.resources.connection_active
import mishka.shared.generated.resources.connection_close_all
import mishka.shared.generated.resources.connection_close_all_summary
import mishka.shared.generated.resources.connection_close_all_title
import mishka.shared.generated.resources.connection_download_total
import mishka.shared.generated.resources.connection_list
import mishka.shared.generated.resources.connection_no_active
import mishka.shared.generated.resources.connection_no_match
import mishka.shared.generated.resources.connection_search
import mishka.shared.generated.resources.connection_start_first
import mishka.shared.generated.resources.connection_title
import mishka.shared.generated.resources.connection_upload_total
import org.jetbrains.compose.resources.stringResource
import top.yukonga.mishka.data.model.ConnectionInfo
import top.yukonga.mishka.ui.component.SearchBarFake
import top.yukonga.mishka.ui.component.SearchBox
import top.yukonga.mishka.ui.component.SearchPager
import top.yukonga.mishka.ui.component.SearchStatus
import top.yukonga.mishka.util.FormatUtils
import top.yukonga.mishka.viewmodel.ConnectionViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.miuixShape
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun ConnectionScreen(
    viewModel: ConnectionViewModel,
    onBack: () -> Unit = {},
    bottomPadding: Dp = 0.dp,
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollBehavior = MiuixScrollBehavior()
    var showCloseAllDialog by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    // 搜索状态
    val searchLabel = stringResource(Res.string.connection_search)
    var searchStatus by remember { mutableStateOf(SearchStatus(label = searchLabel)) }

    // 语言变更时同步 label
    LaunchedEffect(searchLabel) {
        if (searchStatus.label != searchLabel) {
            searchStatus = searchStatus.copy(label = searchLabel)
        }
    }

    // 搜索过滤（直接传入 searchText，避免组合期间写入 ViewModel 状态）
    val searchText = searchStatus.searchText

    val filteredConnections = remember(searchText, uiState.connections) {
        viewModel.filteredConnections(searchText)
    }

    // 更新搜索结果状态
    val resultStatus by remember(searchText, filteredConnections) {
        derivedStateOf {
            when {
                searchText.isEmpty() -> SearchStatus.ResultStatus.DEFAULT
                filteredConnections.isEmpty() -> SearchStatus.ResultStatus.EMPTY
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
                    title = stringResource(Res.string.connection_title),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
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
                        IconButton(onClick = { showCloseAllDialog = true }) {
                            Icon(
                                imageVector = MiuixIcons.Delete,
                                contentDescription = stringResource(Res.string.connection_close_all),
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
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

                    if (filteredConnections.isEmpty()) {
                        item(key = "search_empty") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = stringResource(Res.string.connection_no_match),
                                    fontSize = 16.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    } else {
                        items(filteredConnections, key = { it.id }) { conn ->
                            ConnectionItem(
                                connection = conn,
                                onClose = { viewModel.closeConnection(conn.id) },
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
                if (uiState.connections.isEmpty() && !uiState.isConnected) {
                    item(key = "empty") {
                        Column(
                            modifier = Modifier.fillParentMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = stringResource(Res.string.connection_no_active),
                                fontSize = 16.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Text(
                                text = stringResource(Res.string.connection_start_first),
                                modifier = Modifier.padding(top = 6.dp),
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                } else {
                    // 统计信息
                    item(key = "stats", contentType = "stats") {
                        StatsCard(
                            connectionCount = uiState.connections.size,
                            uploadTotal = uiState.uploadTotal,
                            downloadTotal = uiState.downloadTotal,
                        )
                    }

                    item(key = "connection_title", contentType = "title") {
                        SmallTitle(text = stringResource(Res.string.connection_list))
                    }

                    items(
                        items = uiState.connections,
                        key = { it.id },
                        contentType = { "connection" },
                    ) { conn ->
                        ConnectionItem(
                            connection = conn,
                            onClose = { viewModel.closeConnection(conn.id) },
                        )
                    }

                    item(key = "bottom_spacer", contentType = "spacer") {
                        Spacer(Modifier.navigationBarsPadding())
                    }
                }
            }
        }
    }

    // 关闭全部确认 Dialog
    WindowDialog(
        show = showCloseAllDialog,
        title = stringResource(Res.string.connection_close_all_title),
        summary = stringResource(Res.string.connection_close_all_summary),
        onDismissRequest = { showCloseAllDialog = false },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = stringResource(Res.string.common_cancel),
                modifier = Modifier.weight(1f),
                onClick = { showCloseAllDialog = false },
            )
            TextButton(
                text = stringResource(Res.string.common_confirm),
                modifier = Modifier.weight(1f),
                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    viewModel.closeAllConnections()
                    showCloseAllDialog = false
                },
            )
        }
    }
}

@Composable
private fun StatsCard(
    connectionCount: Int,
    uploadTotal: Long,
    downloadTotal: Long,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        insideMargin = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatItem(label = stringResource(Res.string.connection_active), value = "$connectionCount")
            StatItem(label = stringResource(Res.string.connection_upload_total), value = FormatUtils.formatBytes(uploadTotal))
            StatItem(label = stringResource(Res.string.connection_download_total), value = FormatUtils.formatBytes(downloadTotal))
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ConnectionItem(
    connection: ConnectionInfo,
    onClose: () -> Unit,
) {
    val meta = connection.metadata

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(16.dp),
    ) {
        // 标题行：Host + 关闭按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 网络类型 Badge (TCP/UDP)
            Box(
                modifier = Modifier
                    .clip(miuixShape(3.dp))
                    .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = meta.network.uppercase(),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Text(
                text = meta.host.ifEmpty { "${meta.destinationIP}:${meta.destinationPort}" },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Image(
                modifier = Modifier
                    .size(14.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onClose,
                    ),
                imageVector = MiuixIcons.Close,
                contentDescription = stringResource(Res.string.common_close),
                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
            )
        }

        // 代理链
        if (connection.chains.isNotEmpty()) {
            Text(
                text = connection.chains.joinToString(" → "),
                modifier = Modifier.padding(top = 2.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 规则
        val ruleText = buildString {
            append(connection.rule)
            if (connection.rulePayload.isNotEmpty()) {
                append("(${connection.rulePayload})")
            }
        }
        Text(
            text = ruleText,
            modifier = Modifier.padding(top = 2.dp),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // 底部信息行：流量 + 进程名
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "↑ ${FormatUtils.formatBytes(connection.upload)}  ↓ ${FormatUtils.formatBytes(connection.download)}",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (meta.process.isNotEmpty()) {
                Text(
                    text = meta.process,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
