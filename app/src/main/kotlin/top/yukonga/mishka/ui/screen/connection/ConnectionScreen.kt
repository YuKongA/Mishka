package top.yukonga.mishka.ui.screen.connection

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.mishka.R
import top.yukonga.mishka.domain.model.ConnectionInfo
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.SearchBarFake
import top.yukonga.mishka.ui.component.SearchBox
import top.yukonga.mishka.ui.component.SearchPager
import top.yukonga.mishka.ui.component.SearchResultStatusEffect
import top.yukonga.mishka.ui.component.SearchStatus
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.rememberSearchBarTopPadding
import top.yukonga.mishka.ui.component.rememberSearchScreenStatus
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
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
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun ConnectionScreen(
    viewModel: ConnectionViewModel,
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()
    var showCloseAllDialog by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    // WS 订阅只在本页存续，理由见 startObserving
    DisposableEffect(viewModel) {
        viewModel.startObserving()
        onDispose { viewModel.stopObserving() }
    }

    val searchStatusState = rememberSearchScreenStatus(stringResource(R.string.connection_search))
    var searchStatus by searchStatusState

    // 搜索过滤（直接传入 searchText，避免组合期间写入 ViewModel 状态）
    val searchText = searchStatus.searchText
    val filteredConnections = remember(searchText, uiState.connections) {
        viewModel.filteredConnections(searchText)
    }
    SearchResultStatusEffect(searchStatusState, filteredConnections.isEmpty())

    val dynamicTopPadding = rememberSearchBarTopPadding(scrollBehavior)

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                searchStatus.TopAppBarAnim(
                    backgroundColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface,
                ) {
                    AdaptiveTopAppBar(
                        title = stringResource(R.string.connection_title),
                        color = barColor,
                        scrollBehavior = scrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                val layoutDirection = LocalLayoutDirection.current
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = stringResource(R.string.common_back),
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
                                    contentDescription = stringResource(R.string.connection_close_all),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        },
                        bottomContent = {
                            Box(
                                modifier = Modifier
                                    .graphicsLayer { alpha = if (searchStatus.isCollapsed()) 1f else 0f }
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
                                    text = stringResource(R.string.connection_no_match),
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
                        Spacer(
                            Modifier
                                .height(imeBottomPadding)
                                .navigationBarsPadding(),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        searchStatus.SearchBox {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalCutoutPadding()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
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
                                text = stringResource(R.string.connection_no_active),
                                fontSize = 16.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Text(
                                text = stringResource(R.string.connection_start_first),
                                modifier = Modifier.padding(top = 6.dp),
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                } else {
                    item(key = "stats", contentType = "stats") {
                        StatsCard(
                            connectionCount = uiState.connections.size,
                            uploadTotal = uiState.uploadTotal,
                            downloadTotal = uiState.downloadTotal,
                        )
                    }

                    item(key = "connection_title", contentType = "title") {
                        SmallTitle(text = stringResource(R.string.connection_list))
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
        title = stringResource(R.string.connection_close_all_title),
        summary = stringResource(R.string.connection_close_all_summary),
        onDismissRequest = { showCloseAllDialog = false },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = stringResource(R.string.common_cancel),
                modifier = Modifier.weight(1f),
                onClick = { showCloseAllDialog = false },
            )
            TextButton(
                text = stringResource(R.string.common_confirm),
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
            StatItem(label = stringResource(R.string.connection_active), value = "$connectionCount")
            StatItem(label = stringResource(R.string.connection_upload_total), value = FormatUtils.formatBytes(uploadTotal))
            StatItem(label = stringResource(R.string.connection_download_total), value = FormatUtils.formatBytes(downloadTotal))
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
            Box(
                modifier = Modifier
                    .squircleBackground(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f), 3.dp)
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
                contentDescription = stringResource(R.string.common_close),
                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
            )
        }

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
