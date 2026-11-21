package top.yukonga.mishka.ui.screen.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.mishka.R
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.util.FormatUtils
import top.yukonga.mishka.viewmodel.HomeUiState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.ceil

fun LazyListScope.latencySection(
    state: HomeUiState = HomeUiState(),
    onTestLatency: () -> Unit = {},
) {
    item(key = "latency_title") {
        LatencyHeader(state, onTestLatency)
    }
    item(key = "latency") {
        LatencyCard(state)
    }
}

@Composable
internal fun LatencySectionContent(
    state: HomeUiState = HomeUiState(),
    onTestLatency: () -> Unit = {},
) {
    LatencyHeader(state, onTestLatency)
    LatencyCard(state)
}

@Composable
private fun LatencyCard(state: HomeUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(LocalHomeCardHorizontalPadding.current)
            .padding(bottom = 6.dp),
        insideMargin = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LatencyItem("Xiaomi", state.latencyXiaomi, Alignment.Start)
            LatencyDivider()
            LatencyItem("Cloudflare", state.latencyCloudflare, Alignment.CenterHorizontally)
            LatencyDivider()
            LatencyItem("Google", state.latencyGoogle, Alignment.End)
        }
    }
}

@Composable
private fun LatencyHeader(
    state: HomeUiState,
    onTestLatency: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmallTitle(text = stringResource(R.string.home_latency))
        if (state.isRunning) {
            Row(
                modifier = Modifier.padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!state.latencyViaRules) {
                    Text(
                        text = stringResource(R.string.home_latency_not_via_rules),
                        modifier = Modifier
                            .squircleBackground(StatusColors.warning.copy(alpha = 0.14f), 6.dp)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = StatusColors.warning,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val rotation = remember { Animatable(0f) }
                LaunchedEffect(state.isTestingLatency) {
                    if (state.isTestingLatency) {
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
                    onClick = { if (!state.isTestingLatency) onTestLatency() },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Glass.Refresh,
                        contentDescription = stringResource(R.string.common_refresh),
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer { rotationZ = rotation.value },
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun LatencyDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(24.dp)
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
    )
}

@Composable
private fun LatencyItem(
    name: String,
    delay: Int,
    horizontalAlignment: Alignment.Horizontal,
) {
    // -1 视为"未测/不可达"，由 token 映射到 neutral 灰
    val color = StatusColors.delay(if (delay < 0) null else delay)

    Column(
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = name,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
        )
        Text(
            text = FormatUtils.formatLatency(delay),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
        )
    }
}
