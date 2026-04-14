package top.yukonga.mishka.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.mishka.util.FormatUtils
import top.yukonga.mishka.viewmodel.HomeUiState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

fun LazyListScope.latencySection(
    state: HomeUiState = HomeUiState(),
    onTestLatency: () -> Unit = {},
    onSwitchProxyGroup: (String) -> Unit = {},
) {
    item(key = "latency_title") {
        LatencyHeader(state, onTestLatency, onSwitchProxyGroup)
    }
    item(key = "latency") {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 6.dp),
            insideMargin = PaddingValues(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LatencyItem("Baidu", state.latencyBaidu)
                LatencyItem("Cloudflare", state.latencyCloudflare)
                LatencyItem("Google", state.latencyGoogle)
            }
        }
    }
}

@Composable
private fun LatencyHeader(
    state: HomeUiState,
    onTestLatency: () -> Unit,
    onSwitchProxyGroup: (String) -> Unit,
) {
    val allTested = state.latencyBaidu >= 0 || state.latencyCloudflare >= 0 || state.latencyGoogle >= 0
    val statusColor = when {
        !allTested -> Color(0xFF9E9E9E)
        state.latencyGoogle >= 0 -> Color(0xFF4CAF50)
        state.latencyCloudflare >= 0 -> Color(0xFFFFC107)
        else -> Color(0xFFE53935)
    }
    val statusText = when {
        !allTested -> "未测试"
        state.latencyGoogle >= 0 -> "正常"
        state.latencyCloudflare >= 0 -> "部分"
        else -> "异常"
    }

    var showGroupDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmallTitle(text = "延迟")
        Row(
            modifier = Modifier.padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.isRunning && state.proxyGroups.isNotEmpty()) {
                Text(
                    text = state.selectedProxyGroup,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = "切换",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { showGroupDialog = true }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            if (state.isRunning) {
                Text(
                    text = statusText,
                    fontSize = 12.sp,
                    color = statusColor,
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                IconButton(
                    onClick = onTestLatency,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Refresh,
                        contentDescription = "刷新",
                        modifier = Modifier.size(16.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }

    ProxyGroupSelectDialog(
        show = showGroupDialog,
        groups = state.proxyGroups,
        currentGroup = state.selectedProxyGroup,
        onSelect = { group ->
            onSwitchProxyGroup(group)
            showGroupDialog = false
        },
        onDismiss = { showGroupDialog = false },
    )
}

@Composable
private fun LatencyItem(name: String, delay: Int) {
    val color = when {
        delay < 0 -> Color(0xFF9E9E9E)
        delay < 200 -> Color(0xFF4CAF50)
        delay < 500 -> Color(0xFFFFC107)
        else -> Color(0xFFE53935)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = name,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Text(
            text = FormatUtils.formatLatency(delay),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ProxyGroupSelectDialog(
    show: Boolean,
    groups: List<String>,
    currentGroup: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(show, currentGroup) { mutableStateOf(currentGroup) }

    WindowDialog(
        show = show,
        title = "选择代理组",
        onDismissRequest = onDismiss,
    ) {
        Column {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
            ) {
                items(groups) { group ->
                    TextButton(
                        text = group,
                        onClick = { selected = group },
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (selected == group) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                    )
                    if (group != groups.last()) Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = "确定",
                    onClick = { onSelect(selected) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
