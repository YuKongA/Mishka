package top.yukonga.mishka.ui.screen.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.mishka.BuildConfig
import top.yukonga.mishka.R
import top.yukonga.mishka.platform.TunMode
import top.yukonga.mishka.ui.component.GlassDialogTextButton
import top.yukonga.mishka.ui.component.blur.LocalPageBackdrop
import top.yukonga.mishka.ui.theme.ActionKind
import top.yukonga.mishka.ui.theme.ActionPalette
import top.yukonga.mishka.ui.theme.LocalAppDarkMode
import top.yukonga.mishka.ui.theme.RunState
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.viewmodel.HomeUiState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextButtonColors
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import kotlin.math.ceil

fun LazyListScope.statusSection(
    state: HomeUiState = HomeUiState(),
    uptime: String = "",
    onSwitchMode: (String) -> Unit = {},
    onSwitchTunStack: (String) -> Unit = {},
    onRestart: () -> Unit = {},
    onStop: () -> Unit = {},
    onStart: () -> Unit = {},
) {
    item(key = "status") {
        StatusContent(
            state = state,
            uptime = uptime,
            onSwitchMode = onSwitchMode,
            onSwitchTunStack = onSwitchTunStack,
            onRestart = onRestart,
            onStop = onStop,
            onStart = onStart,
        )
    }
}

@Composable
internal fun StatusContent(
    state: HomeUiState,
    uptime: String,
    onSwitchMode: (String) -> Unit,
    onSwitchTunStack: (String) -> Unit,
    onRestart: () -> Unit,
    onStop: () -> Unit,
    onStart: () -> Unit,
) {
    val isRunning = state.isRunning
    val isStarting = state.isStarting
    val isStopping = state.isStopping

    val runState = when {
        isStarting || isStopping || state.isRestarting -> RunState.Pending
        isRunning -> RunState.Running
        else -> RunState.Stopped
    }
    val statusTint = StatusColors.runState(runState)
    val statusContainer = StatusColors.runStateContainer(runState)

    var showModeDialog by remember { mutableStateOf(false) }
    var showTunStackDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(LocalHomeCardHorizontalPadding.current)
            .padding(top = 12.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 顶部左右结构：左状态卡（右下角 Badge）+ 右双配置卡
        val isTproxy = state.tunMode == TunMode.RootTproxy
        val inboundTproxyLabel = stringResource(R.string.home_inbound_tproxy, TPROXY_INBOUND_PORT)
        val statusCard = @Composable { modifier: Modifier ->
            Card(
                modifier = modifier,
                colors = CardDefaults.defaultColors(color = statusContainer),
            ) {
                val statusIcon = if (isRunning) CheckCircleOutlineIcon else RemoveCircleOutlineIcon
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .offset(30.dp, 36.dp),
                        contentAlignment = Alignment.BottomEnd,
                    ) {
                        Icon(
                            modifier = Modifier.size(136.dp),
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
                            text = when {
                                state.isRestarting -> stringResource(R.string.home_restarting)
                                isStopping -> stringResource(R.string.home_stopping)
                                isStarting -> stringResource(R.string.home_starting)
                                isRunning -> stringResource(R.string.home_running)
                                else -> stringResource(R.string.home_stopped)
                            },
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = statusTint,
                        )
                        Spacer(Modifier.height(2.dp))
                        val versionText = state.version.ifBlank { BuildConfig.MIHOMO_VERSION }
                        Text(
                            text = versionText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        if (uptime.isNotBlank()) {
                            Text(
                                text = uptime,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }
        val modeCard = @Composable { modifier: Modifier ->
            Card(
                modifier = modifier,
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                onClick = { showModeDialog = true },
                showIndication = true,
                pressFeedbackType = PressFeedbackType.Sink,
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
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
                    Icon(
                        imageVector = MiuixIcons.Glass.ChevronForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
                    )
                }
            }
        }
        val tunStackCard = @Composable { modifier: Modifier ->
            Card(
                modifier = modifier,
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                onClick = { if (isRunning && !isTproxy) showTunStackDialog = true },
                showIndication = isRunning && !isTproxy,
                pressFeedbackType = if (isRunning && !isTproxy) PressFeedbackType.Sink else PressFeedbackType.None,
            ) {
                val clickable = isRunning && !isTproxy
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
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
                    if (clickable) {
                        Icon(
                            imageVector = MiuixIcons.Glass.ChevronForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
        val actionButtons = @Composable {
            if (isRunning || state.isRestarting) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        text = stringResource(R.string.home_stop),
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        enabled = !state.isRestarting,
                        colors = StatusColors.actionButton(ActionKind.Stop).asTextButtonColors(),
                    )
                    val restartColors = StatusColors.actionButton(ActionKind.Restart)
                    val rotation = remember { Animatable(0f) }
                    LaunchedEffect(state.isRestarting) {
                        if (state.isRestarting) {
                            while (true) {
                                rotation.animateTo(
                                    targetValue = rotation.value + 360f,
                                    animationSpec = tween(durationMillis = 800, easing = LinearEasing),
                                )
                            }
                        } else if (rotation.value % 360f != 0f) {
                            val target = ceil(rotation.value / 360f) * 360f
                            rotation.animateTo(
                                targetValue = target,
                                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                            )
                            rotation.snapTo(0f)
                        }
                    }
                    IconButton(
                        onClick = { if (!state.isRestarting) onRestart() },
                        modifier = Modifier.size(48.dp),
                        backgroundColor = restartColors.container,
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Glass.Refresh,
                            contentDescription = stringResource(R.string.home_restart),
                            modifier = Modifier.graphicsLayer { rotationZ = rotation.value },
                            tint = restartColors.content,
                        )
                    }
                }
            } else if (isStopping) {
                TextButton(
                    text = stringResource(R.string.home_stopping_btn),
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    enabled = false,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            } else {
                TextButton(
                    text = if (isStarting) stringResource(R.string.home_starting_btn) else stringResource(R.string.home_start),
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isStarting,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            statusCard(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                modeCard(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                Spacer(Modifier.height(12.dp))
                tunStackCard(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }

        actionButtons()
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

private fun ActionPalette.asTextButtonColors(): TextButtonColors = TextButtonColors(
    color = container,
    disabledColor = container.copy(alpha = 0.5f),
    textColor = content,
    disabledTextColor = content.copy(alpha = 0.5f),
)

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

    GlassDialog(
        show = show,
        backdrop = LocalPageBackdrop.current,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Column {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.onBackground.copy(
                        alpha = if (LocalAppDarkMode.current) 36f / 255f else 15f / 255f,
                    ),
                ),
            ) {
                options.forEach { (value, label) ->
                    RadioButtonPreference(
                        title = label,
                        selected = value == selected,
                        radioButtonLocation = RadioButtonLocation.End,
                        onClick = {
                            if (value != selected) {
                                currentOnSelect(value)
                            }
                            onDismiss()
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            GlassDialogTextButton(
                text = stringResource(R.string.common_cancel),
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss,
            )
        }
    }
}

private val CheckCircleOutlineIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "CheckCircleOutline",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 2f)
        curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
        reflectiveCurveToRelative(4.48f, 10f, 10f, 10f)
        reflectiveCurveToRelative(10f, -4.48f, 10f, -10f)
        reflectiveCurveTo(17.52f, 2f, 12f, 2f)
        close()
        moveTo(12f, 20f)
        curveToRelative(-4.41f, 0f, -8f, -3.59f, -8f, -8f)
        reflectiveCurveToRelative(3.59f, -8f, 8f, -8f)
        reflectiveCurveToRelative(8f, 3.59f, 8f, 8f)
        reflectiveCurveToRelative(-3.59f, 8f, -8f, 8f)
        close()
        moveTo(15.88f, 8.29f)
        lineTo(10f, 14.17f)
        lineToRelative(-1.88f, -1.88f)
        curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0f)
        curveToRelative(-0.39f, 0.39f, -0.39f, 1.02f, 0f, 1.41f)
        lineToRelative(2.59f, 2.59f)
        curveToRelative(0.39f, 0.39f, 1.02f, 0.39f, 1.41f, 0f)
        lineToRelative(6.59f, -6.59f)
        curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0f, -1.41f)
        curveToRelative(-0.39f, -0.39f, -1.03f, -0.39f, -1.42f, 0f)
        close()
    }.build()
}

private val RemoveCircleOutlineIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "RemoveCircleOutline",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(Color.Black)) {
        moveTo(7f, 12f)
        curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
        horizontalLineToRelative(8f)
        curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
        reflectiveCurveToRelative(-0.45f, -1f, -1f, -1f)
        horizontalLineTo(8f)
        curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
        close()
        moveTo(12f, 2f)
        curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
        reflectiveCurveToRelative(4.48f, 10f, 10f, 10f)
        reflectiveCurveToRelative(10f, -4.48f, 10f, -10f)
        reflectiveCurveTo(17.52f, 2f, 12f, 2f)
        close()
        moveTo(12f, 20f)
        curveToRelative(-4.41f, 0f, -8f, -3.59f, -8f, -8f)
        reflectiveCurveToRelative(3.59f, -8f, 8f, -8f)
        reflectiveCurveToRelative(8f, 3.59f, 8f, 8f)
        reflectiveCurveToRelative(-3.59f, 8f, -8f, 8f)
        close()
    }.build()
}
