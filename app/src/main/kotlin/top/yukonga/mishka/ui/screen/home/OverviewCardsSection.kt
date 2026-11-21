package top.yukonga.mishka.ui.screen.home

import androidx.compose.animation.CapturedAnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import top.yukonga.mishka.R
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.util.FormatUtils
import top.yukonga.mishka.viewmodel.HomeUiState
import top.yukonga.mishka.viewmodel.SpeedSnapshot
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * 「概览」分组第一行：实时网速（带流量折线图）+ 订阅用量（可点开代理集合弹窗）。
 * 本行的标题同时统辖 [systemCardsSection] 的第二行，四张卡共处一个分组。
 */
fun LazyListScope.overviewCardsSection(
    speed: SpeedSnapshot = SpeedSnapshot(),
    state: HomeUiState = HomeUiState(),
    onSpeedClick: () -> Unit = {},
    onSubscriptionClick: () -> Unit = {},
) {
    item(key = "overview_title") {
        SmallTitle(text = stringResource(R.string.home_overview))
    }
    item(key = "overview_speed") {
        SpeedHeroCard(
            speed = speed,
            isRunning = state.isRunning,
            onSpeedClick = onSpeedClick,
        )
    }
    item(key = "overview_subscription") {
        val total = state.subscription?.Total?.coerceAtLeast(0) ?: 0
        val used = state.subscription?.let { (it.Upload + it.Download).coerceAtLeast(0) } ?: 0
        val hasQuota = total > 0
        CapturedAnimatedVisibility(
            visible = hasQuota,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            SubscriptionQuotaCard(
                used = used,
                total = total,
                expire = state.subscription?.Expire ?: 0L,
                isRunning = state.isRunning,
                onClick = onSubscriptionClick,
            )
        }
    }
}

@Composable
internal fun OverviewCardsContent(
    speed: SpeedSnapshot = SpeedSnapshot(),
    state: HomeUiState = HomeUiState(),
    onSpeedClick: () -> Unit = {},
    onSubscriptionClick: () -> Unit = {},
) {
    SmallTitle(text = stringResource(R.string.home_overview))
    SpeedHeroCard(
        speed = speed,
        isRunning = state.isRunning,
        onSpeedClick = onSpeedClick,
    )
    val total = state.subscription?.Total?.coerceAtLeast(0) ?: 0
    val used = state.subscription?.let { (it.Upload + it.Download).coerceAtLeast(0) } ?: 0
    val hasQuota = total > 0
    CapturedAnimatedVisibility(
        visible = hasQuota,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        SubscriptionQuotaCard(
            used = used,
            total = total,
            expire = state.subscription?.Expire ?: 0L,
            isRunning = state.isRunning,
            onClick = onSubscriptionClick,
        )
    }
}

@Composable
private fun SpeedHeroCard(
    speed: SpeedSnapshot,
    isRunning: Boolean,
    onSpeedClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(LocalHomeCardHorizontalPadding.current)
            .padding(bottom = 6.dp),
        insideMargin = PaddingValues(0.dp),
        onClick = if (isRunning) onSpeedClick else null,
        showIndication = isRunning,
        pressFeedbackType = if (isRunning) PressFeedbackType.Sink else PressFeedbackType.None,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            TrafficSparkline(
                modifier = Modifier.matchParentSize(),
                history = speed.history,
                uploadColor = StatusColors.trafficUpload,
                downloadColor = StatusColors.trafficDownload,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.home_speed),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    BadgeLabel("NET")
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.home_upload),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            text = speed.uploadSpeed,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.home_download),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            text = speed.downloadSpeed,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionQuotaCard(
    used: Long,
    total: Long,
    expire: Long,
    isRunning: Boolean,
    onClick: () -> Unit,
) {
    val progress = if (total > 0) (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    val animatedProgress = animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 700),
        label = "subscription_progress",
    )
    val expireLabel = remember(expire) {
        if (expire > 0L) {
            val dt = Instant.fromEpochSeconds(expire).toLocalDateTime(TimeZone.currentSystemDefault())
            val m = dt.month.number.toString().padStart(2, '0')
            val d = dt.day.toString().padStart(2, '0')
            "${dt.year}-$m-$d"
        } else {
            null
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(LocalHomeCardHorizontalPadding.current)
            .padding(top = 6.dp, bottom = 6.dp),
        insideMargin = PaddingValues(16.dp),
        onClick = if (isRunning) onClick else null,
        showIndication = isRunning,
        pressFeedbackType = if (isRunning) PressFeedbackType.Sink else PressFeedbackType.None,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_subscription),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            BadgeLabel("${(progress * 100).roundToInt()}%")
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = FormatUtils.formatBytes(used),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = " / ${FormatUtils.formatBytes(total)}",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            if (expireLabel != null) {
                Text(
                    text = "${stringResource(R.string.home_expire)} $expireLabel",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        val trackColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        val barColor = StatusColors.usage(progress)
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .drawBehind {
                    val radius = CornerRadius(size.height / 2f, size.height / 2f)
                    drawRoundRect(color = trackColor, cornerRadius = radius)
                    val fillWidth = size.width * animatedProgress.value
                    if (fillWidth > 0f) {
                        drawRoundRect(
                            color = barColor,
                            size = Size(fillWidth, size.height),
                            cornerRadius = radius,
                        )
                    }
                },
        )
    }
}
