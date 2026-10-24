package top.yukonga.mishka.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.mishka.R
import top.yukonga.mishka.platform.TunMode
import top.yukonga.mishka.ui.theme.RunState
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.viewmodel.HomeUiState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.popup.WindowDropdownDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

fun LazyListScope.statusSection(
    state: HomeUiState = HomeUiState(),
    uptime: String = "",
    onSwitchMode: (String) -> Unit = {},
    onSwitchTunStack: (String) -> Unit = {},
) {
    item(key = "status") {
        StatusContent(state, uptime, onSwitchMode, onSwitchTunStack)
    }
}

@Composable
private fun StatusContent(
    state: HomeUiState,
    uptime: String,
    onSwitchMode: (String) -> Unit,
    onSwitchTunStack: (String) -> Unit,
) {
    val isRunning = state.isRunning
    val isStarting = state.isStarting
    val isStopping = state.isStopping

    val statusIcon = if (isRunning) {
        Icons.Rounded.CheckCircleOutline
    } else {
        Icons.Rounded.RemoveCircleOutline
    }
    val runState = when {
        isStarting || isStopping -> RunState.Pending
        isRunning -> RunState.Running
        else -> RunState.Stopped
    }
    val statusTint = StatusColors.runState(runState)
    val statusContainer = StatusColors.runStateContainer(runState)

    var showModeDialog by remember { mutableStateOf(false) }
    var showTunStackDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp, bottom = 6.dp)
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧状态卡片
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            colors = CardDefaults.defaultColors(color = statusContainer),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(38.dp, 45.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Icon(
                        modifier = Modifier.size(170.dp),
                        imageVector = statusIcon,
                        tint = statusTint,
                        contentDescription = null,
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(all = 16.dp),
                ) {
                    Text(
                        text = if (isStopping) {
                            stringResource(R.string.home_stopping)
                        } else if (isStarting) {
                            stringResource(R.string.home_starting)
                        } else if (isRunning) {
                            stringResource(R.string.home_running)
                        } else {
                            stringResource(R.string.home_stopped)
                        },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = statusTint,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = state.version,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = uptime,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        // 右侧卡片
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                insideMargin = PaddingValues(16.dp),
                // 停止态也可切：切的是 override，运行中才顺带重启
                onClick = { showModeDialog = true },
                showIndication = true,
                pressFeedbackType = PressFeedbackType.Sink,
            ) {
                Text(
                    text = stringResource(R.string.home_mode),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = modeLabel(state.mode),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(12.dp))
            // RootTproxy 下 tun.enable=false，stack 无意义：展示 Inbound=TPROXY:port 静态信息
            val isTproxy = state.tunMode == TunMode.RootTproxy
            val inboundTproxyLabel = stringResource(R.string.home_inbound_tproxy, TPROXY_INBOUND_PORT)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                insideMargin = PaddingValues(16.dp),
                onClick = { if (isRunning && !isTproxy) showTunStackDialog = true },
                showIndication = isRunning && !isTproxy,
                pressFeedbackType = if (isRunning && !isTproxy) PressFeedbackType.Sink else PressFeedbackType.None,
            ) {
                Text(
                    text = if (isTproxy) stringResource(R.string.home_inbound) else stringResource(R.string.home_tun),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = if (isTproxy) inboundTproxyLabel else tunStackLabel(state.tunStack),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }

    OptionSelectDialog(
        show = showModeDialog,
        title = stringResource(R.string.home_switch_mode),
        options = MODE_OPTIONS,
        current = state.mode,
        onSelect = onSwitchMode,
        onDismiss = { showModeDialog = false },
    )

    OptionSelectDialog(
        show = showTunStackDialog,
        title = stringResource(R.string.home_switch_tun_stack),
        options = TUN_STACK_OPTIONS,
        current = state.tunStack,
        onSelect = onSwitchTunStack,
        onDismiss = { showTunStackDialog = false },
    )
}

// ROOT TPROXY 模式 mihomo tproxy-port（与 RootTproxyApplier.TPROXY_PORT 对齐，UI 仅展示用）
private const val TPROXY_INBOUND_PORT: Int = 7895

// value（mihomo 侧小写标识）to label（展示名），同时供卡片取值与选择弹窗列举
private val MODE_OPTIONS = listOf("rule" to "Rule", "global" to "Global", "direct" to "Direct")

private val TUN_STACK_OPTIONS = listOf("mixed" to "Mixed", "gvisor" to "gVisor", "system" to "System", "mips" to "MIPS")

private fun modeLabel(mode: String): String =
    MODE_OPTIONS.firstOrNull { it.first == mode.lowercase() }?.second ?: mode.ifEmpty { "--" }

private fun tunStackLabel(stack: String): String =
    TUN_STACK_OPTIONS.firstOrNull { it.first == stack.lowercase() }?.second ?: stack.ifEmpty { "--" }

/**
 * 单选弹窗：点选即生效并自行关闭（`collapseOnSelection` 默认 true），底部只留取消。
 * 选中项的高亮底色 + 勾选走 [DropdownDefaults.dialogDropdownColors]。
 */
@Composable
private fun OptionSelectDialog(
    show: Boolean,
    title: String,
    options: List<Pair<String, String>>,
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentOnSelect by rememberUpdatedState(onSelect)
    val selected = current.lowercase()
    // onSelect 不进 key：调用点每次重组都可能给出新 lambda，会让整个 entry 白重建
    val entry = remember(options, selected) {
        DropdownEntry(
            options.map { (value, label) ->
                DropdownItem(
                    text = label,
                    selected = value == selected,
                    onClick = { currentOnSelect(value) },
                )
            },
        )
    }

    WindowDropdownDialog(
        entry = entry,
        title = title,
        dialogButtonString = stringResource(R.string.common_cancel),
        show = show,
        onDismiss = onDismiss,
        onDismissFinished = {},
        dropdownColors = DropdownDefaults.dialogDropdownColors(),
    )
}
