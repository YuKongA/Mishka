package top.yukonga.mishka.ui.screen.subscription

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.mishka.data.model.Subscription
import top.yukonga.mishka.util.FormatUtils
import top.yukonga.mishka.viewmodel.SubscriptionViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.miuixShape
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 订阅列表页面
 */
@Composable
fun SubscriptionScreen(
    viewModel: SubscriptionViewModel,
    bottomPadding: Dp = 0.dp,
    onBack: (() -> Unit)? = null,
    onNavigateAdd: () -> Unit = {},
    onNavigateEdit: (uuid: String) -> Unit = {},
    onDuplicate: (uuid: String) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = "订阅管理",
                scrollBehavior = scrollBehavior,
                navigationIcon = if (onBack != null) {
                    {
                        IconButton(onClick = onBack) {
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
                    }
                } else {
                    {}
                },
                actions = {
                    if (uiState.subscriptions.any { it.url.isNotBlank() }) {
                        IconButton(
                            onClick = { viewModel.updateAllSubscriptions() },
                            enabled = !uiState.isLoading,
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = "全部更新",
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.clearError(); onNavigateAdd() }) {
                        Icon(
                            imageVector = MiuixIcons.Add,
                            contentDescription = "添加",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
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
            if (uiState.error.isNotEmpty()) {
                item(key = "error") {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 12.dp, bottom = 6.dp),
                        insideMargin = PaddingValues(16.dp),
                    ) {
                        Text(
                            text = uiState.error,
                            color = Color(0xFFE53935),
                        )
                    }
                }
            }

            if (uiState.subscriptions.isEmpty()) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier.fillParentMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "暂无配置",
                            fontSize = 16.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            text = "点击右上角 + 添加配置",
                            modifier = Modifier.padding(top = 6.dp),
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            if (uiState.subscriptions.isNotEmpty()) {
                item(key = "sub_title") {
                    SmallTitle(text = "配置列表")
                }
            }

            items(uiState.subscriptions, key = { it.id }) { sub ->
                SubscriptionItem(
                    subscription = sub,
                    isLoading = uiState.isLoading,
                    onSelect = { viewModel.setActive(sub.id) },
                    onRefresh = { viewModel.fetchSubscription(sub.id) },
                    onDelete = { viewModel.removeSubscription(sub.id) },
                    onEdit = { onNavigateEdit(sub.id) },
                    onDuplicate = { onDuplicate(sub.id) },
                )
            }
        }
    }
}

@Composable
private fun SubscriptionItem(
    subscription: Subscription,
    isLoading: Boolean,
    onSelect: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(16.dp),
        onClick = onSelect,
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = subscription.name.ifBlank { "配置" },
                modifier = Modifier.weight(1f),
                fontSize = 17.sp,
                fontWeight = FontWeight(550),
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (subscription.isActive) {
                Text(
                    text = "使用中",
                    fontSize = 12.sp,
                    fontWeight = FontWeight(750),
                    color = Color(0xFF4CAF50),
                    modifier = Modifier
                        .clip(miuixShape(6.dp))
                        .background(Color(0xFF4CAF50).copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        if (subscription.url.isNotEmpty()) {
            Text(
                text = subscription.url,
                modifier = Modifier.padding(top = 2.dp),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Row {

            Column(
                modifier = Modifier.wrapContentSize()
            ) {
                if (subscription.total > 0) {
                    val used = subscription.upload + subscription.download
                    Text(
                        text = "已用 ${FormatUtils.formatBytes(used)} / ${FormatUtils.formatBytes(subscription.total)}",
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    Text(
                        text = "未获取到流量信息",
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                Spacer(Modifier.weight(1f))

                if (subscription.updatedAt > 0) {
                    Text(
                        text = "更新于 ${formatTime(subscription.updatedAt)}",
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            IconButton(
                onClick = onEdit,
                minHeight = 35.dp,
                minWidth = 35.dp,
                backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = MiuixIcons.More,
                    contentDescription = "编辑",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onRefresh,
                enabled = !isLoading && subscription.url.isNotBlank(),
                minHeight = 35.dp,
                minWidth = 35.dp,
                backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = MiuixIcons.Refresh,
                    contentDescription = "更新",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onDelete,
                minHeight = 35.dp,
                minWidth = 35.dp,
                backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = MiuixIcons.Delete,
                    contentDescription = "删除",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}
