package top.yukonga.mishka.ui.screen.home

import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.mishka.viewmodel.HomeUiState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.window.WindowDialog
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

fun LazyListScope.statusSection(
    state: HomeUiState = HomeUiState(),
    onSwitchMode: (String) -> Unit = {},
    onSwitchTunStack: (String) -> Unit = {},
) {
    item(key = "status") {
        StatusContent(state, onSwitchMode, onSwitchTunStack)
    }
}

@Composable
private fun StatusContent(
    state: HomeUiState,
    onSwitchMode: (String) -> Unit,
    onSwitchTunStack: (String) -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val isRunning = state.isRunning
    val isStarting = state.isStarting

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
            modifier = Modifier.weight(1f).fillMaxHeight(),
            colors = CardDefaults.defaultColors(
                color = if (isStarting) {
                    if (isDark) Color(0xFF3A3420) else Color(0xFFFFF8E1)
                } else if (isRunning) {
                    if (isDark) Color(0xFF1A3825) else Color(0xFFDFFAE4)
                } else {
                    if (isDark) Color(0xFF3A2020) else Color(0xFFFDE8E8)
                },
            ),
            onClick = { },
            pressFeedbackType = PressFeedbackType.Tilt,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (isRunning) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .offset(38.dp, 45.dp),
                        contentAlignment = Alignment.BottomEnd,
                    ) {
                        Icon(
                            modifier = Modifier.size(170.dp),
                            imageVector = Icons.Rounded.CheckCircleOutline,
                            tint = if (isDark) Color(0xFF2E7D32) else Color(0xFF36D167),
                            contentDescription = null,
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(all = 16.dp),
                ) {
                    Text(
                        text = if (isStarting) stringResource(Res.string.home_starting) else if (isRunning) stringResource(Res.string.home_running) else stringResource(Res.string.home_stopped),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isStarting) {
                            if (isDark) Color(0xFFFFCC80) else Color(0xFFE65100)
                        } else if (isRunning) {
                            if (isDark) Color(0xFF81C784) else Color(0xFF4CAF50)
                        } else {
                            if (isDark) Color(0xFFEF9A9A) else Color(0xFFE53935)
                        },
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = state.version,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isDark) Color(0xFFAAAAAA) else Color(0xFF666666),
                    )
                    Text(
                        text = state.uptime,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isDark) Color(0xFFAAAAAA) else Color(0xFF666666),
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
                modifier = Modifier.fillMaxWidth().weight(1f),
                insideMargin = PaddingValues(16.dp),
                onClick = { if (isRunning) showModeDialog = true },
                pressFeedbackType = PressFeedbackType.Sink,
            ) {
                Text(
                    text = stringResource(Res.string.home_mode),
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
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                insideMargin = PaddingValues(16.dp),
                onClick = { if (isRunning) showTunStackDialog = true },
                pressFeedbackType = PressFeedbackType.Sink,
            ) {
                Text(
                    text = "TUN",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = tunStackLabel(state.tunStack),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }

    // 模式切换 Dialog
    ModeSelectDialog(
        show = showModeDialog,
        currentMode = state.mode,
        onSelect = { mode ->
            onSwitchMode(mode)
            showModeDialog = false
        },
        onDismiss = { showModeDialog = false },
    )

    // TUN Stack 切换 Dialog
    TunStackSelectDialog(
        show = showTunStackDialog,
        currentStack = state.tunStack,
        onSelect = { stack ->
            onSwitchTunStack(stack)
            showTunStackDialog = false
        },
        onDismiss = { showTunStackDialog = false },
    )
}

private fun modeLabel(mode: String): String = when (mode.lowercase()) {
    "rule" -> "Rule"
    "global" -> "Global"
    "direct" -> "Direct"
    else -> mode.ifEmpty { "--" }
}

private fun tunStackLabel(stack: String): String = when (stack.lowercase()) {
    "mixed" -> "Mixed"
    "gvisor" -> "gVisor"
    "system" -> "System"
    else -> stack.ifEmpty { "--" }
}

@Composable
private fun ModeSelectDialog(
    show: Boolean,
    currentMode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val modes = listOf("rule" to "Rule", "global" to "Global", "direct" to "Direct")
    var selected by remember(show, currentMode) { mutableStateOf(currentMode.lowercase()) }

    WindowDialog(
        show = show,
        title = stringResource(Res.string.home_switch_mode),
        onDismissRequest = onDismiss,
    ) {
        Column {
            modes.forEachIndexed { index, (value, label) ->
                TextButton(
                    text = label,
                    onClick = { selected = value },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (selected == value) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                )
                if (index < modes.lastIndex) Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = stringResource(Res.string.common_confirm),
                    onClick = { onSelect(selected) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = stringResource(Res.string.common_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TunStackSelectDialog(
    show: Boolean,
    currentStack: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val stacks = listOf("mixed" to "Mixed", "gvisor" to "gVisor", "system" to "System")
    var selected by remember(show, currentStack) { mutableStateOf(currentStack.lowercase()) }

    WindowDialog(
        show = show,
        title = stringResource(Res.string.home_switch_tun_stack),
        onDismissRequest = onDismiss,
    ) {
        Column {
            stacks.forEachIndexed { index, (value, label) ->
                TextButton(
                    text = label,
                    onClick = { selected = value },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (selected == value) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                )
                if (index < stacks.lastIndex) Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = stringResource(Res.string.common_confirm),
                    onClick = { onSelect(selected) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = stringResource(Res.string.common_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
