package top.yukonga.mishka.ui.screen.connection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.mishka.data.model.ConnectionInfo
import top.yukonga.mishka.util.FormatUtils
import top.yukonga.mishka.viewmodel.ConnectionViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Search
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
    var isSearchMode by remember { mutableStateOf(false) }
    val searchFieldState = rememberTextFieldState()
    var showCloseAllDialog by remember { mutableStateOf(false) }

    val filteredConnections = remember(uiState.searchQuery, uiState.connections) {
        viewModel.filteredConnections()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = if (isSearchMode) "" else "连接",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (isSearchMode) {
                                isSearchMode = false
                                viewModel.setSearchQuery("")
                            } else {
                                onBack()
                            }
                        },
                    ) {
                        val layoutDirection = LocalLayoutDirection.current
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.graphicsLayer {
                                scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                            },
                        )
                    }
                },
                actions = {
                    if (isSearchMode) {
                        TextField(
                            state = searchFieldState,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                            label = "搜索连接",
                            useLabelAsPlaceholder = true,
                        )
                    } else {
                        IconButton(onClick = { isSearchMode = true }) {
                            Icon(
                                imageVector = MiuixIcons.Search,
                                contentDescription = "搜索",
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = { showCloseAllDialog = true }) {
                            Icon(
                                imageVector = MiuixIcons.Delete,
                                contentDescription = "关闭全部",
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        // 监听搜索框文本变化
        val searchText = searchFieldState.text.toString()
        viewModel.setSearchQuery(if (isSearchMode) searchText else "")

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
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (uiState.connections.isEmpty() && !uiState.isConnected) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier.fillParentMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "暂无活跃连接",
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
                }
            } else {
                // 统计信息
                item(key = "stats") {
                    StatsCard(
                        connectionCount = filteredConnections.size,
                        uploadTotal = uiState.uploadTotal,
                        downloadTotal = uiState.downloadTotal,
                    )
                }

                item(key = "connection_title") {
                    SmallTitle(text = "连接列表")
                }

                if (filteredConnections.isEmpty() && uiState.searchQuery.isNotBlank()) {
                    item(key = "search_empty") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 6.dp),
                            insideMargin = PaddingValues(16.dp),
                        ) {
                            Text(
                                text = "无匹配结果",
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }

                items(filteredConnections, key = { it.id }) { conn ->
                    ConnectionItem(
                        connection = conn,
                        onClose = { viewModel.closeConnection(conn.id) },
                    )
                }

                item(key = "bottom_spacer") {
                    Spacer(Modifier.navigationBarsPadding())
                }
            }
        }
    }

    // 关闭全部确认 Dialog
    WindowDialog(
        show = showCloseAllDialog,
        title = "关闭所有连接",
        summary = "确定要关闭所有活跃连接吗？",
        onDismissRequest = { showCloseAllDialog = false },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = "取消",
                modifier = Modifier.weight(1f),
                onClick = { showCloseAllDialog = false },
            )
            TextButton(
                text = "确定",
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
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp, bottom = 6.dp),
        insideMargin = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatItem(label = "活跃连接", value = "$connectionCount")
            StatItem(label = "上行总量", value = FormatUtils.formatBytes(uploadTotal))
            StatItem(label = "下行总量", value = FormatUtils.formatBytes(downloadTotal))
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
            .padding(bottom = 6.dp),
        insideMargin = PaddingValues(12.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 第一行：网络类型 Badge + Host
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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

                // Host
                Text(
                    text = meta.host.ifEmpty { "${meta.destinationIP}:${meta.destinationPort}" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                // 关闭按钮
                IconButton(
                    onClick = onClose,
                    minHeight = 28.dp,
                    minWidth = 28.dp,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Close,
                        modifier = Modifier.size(14.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        contentDescription = "关闭",
                    )
                }
            }

            // 第二行：规则 + 代理链
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val ruleText = buildString {
                    append(connection.rule)
                    if (connection.rulePayload.isNotEmpty()) {
                        append("(${connection.rulePayload})")
                    }
                }
                Text(
                    text = ruleText,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (connection.chains.isNotEmpty()) {
                    Text(
                        text = connection.chains.joinToString(" → "),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.primary.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 第三行：流量 + 进程名
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "↑${FormatUtils.formatBytes(connection.upload)} ↓${FormatUtils.formatBytes(connection.download)}",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (meta.process.isNotEmpty()) {
                    Text(
                        text = meta.process,
                        modifier = Modifier.padding(start = 8.dp),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
