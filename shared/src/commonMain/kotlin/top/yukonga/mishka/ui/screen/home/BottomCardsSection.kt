package top.yukonga.mishka.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.common_close
import mishka.shared.generated.resources.home_expire
import mishka.shared.generated.resources.home_no_expire
import mishka.shared.generated.resources.home_remaining
import mishka.shared.generated.resources.home_subscription
import mishka.shared.generated.resources.home_subscription_no_provider_traffic
import mishka.shared.generated.resources.home_subscription_provider_traffic
import mishka.shared.generated.resources.home_system
import mishka.shared.generated.resources.home_total
import mishka.shared.generated.resources.home_used
import org.jetbrains.compose.resources.stringResource
import top.yukonga.mishka.util.FormatUtils
import top.yukonga.mishka.viewmodel.HomeUiState
import top.yukonga.mishka.viewmodel.MemorySnapshot
import top.yukonga.mishka.viewmodel.ProviderTrafficInfo
import top.yukonga.mishka.viewmodel.SystemInfoSnapshot
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import kotlin.math.roundToInt
import kotlin.time.Instant

fun LazyListScope.bottomCardsSection(
    state: HomeUiState = HomeUiState(),
    memory: MemorySnapshot = MemorySnapshot(),
    systemInfo: SystemInfoSnapshot = SystemInfoSnapshot(),
    onSubscriptionClick: () -> Unit = {},
) {
    item(key = "bottom_cards") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 6.dp, bottom = 12.dp)
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                insideMargin = PaddingValues(16.dp),
                onClick = onSubscriptionClick,
                pressFeedbackType = PressFeedbackType.Sink,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.home_subscription),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    BadgeLabel("SUB")
                }
                InfoRow(
                    stringResource(Res.string.home_used),
                    state.subscription?.let { FormatUtils.formatBytes(it.Upload + it.Download) } ?: "--",
                    Modifier.padding(top = 8.dp)
                )
                InfoRow(
                    stringResource(Res.string.home_total),
                    state.subscription?.let { FormatUtils.formatBytes(it.Total) } ?: "--",
                    Modifier.padding(top = 4.dp)
                )
            }

            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                insideMargin = PaddingValues(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.home_system),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    BadgeLabel("SYS")
                }
                InfoRow(
                    "CPU",
                    systemInfo.cpuUsage,
                    Modifier.padding(top = 8.dp),
                )
                InfoRow(
                    "RAM",
                    memory.ramUsage,
                    Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
internal fun SubscriptionTrafficDialog(
    show: Boolean,
    providers: List<ProviderTrafficInfo>,
    onDismiss: () -> Unit,
) {
    WindowBottomSheet(
        show = show,
        title = stringResource(Res.string.home_subscription_provider_traffic),
        onDismissRequest = onDismiss,
        endAction = {
            val dismiss = LocalDismissState.current
            IconButton(onClick = { dismiss?.invoke() }) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = stringResource(Res.string.common_close),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp),
        ) {
            if (providers.isEmpty()) {
                Text(
                    text = stringResource(Res.string.home_subscription_no_provider_traffic),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 28.dp),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 56.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        items = providers,
                        key = { it.id },
                    ) { provider ->
                        ProviderTrafficCard(provider)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderTrafficCard(provider: ProviderTrafficInfo) {
    val used = provider.upload + provider.download
    val remaining = (provider.total - used).coerceAtLeast(0)
    val progress = if (provider.total > 0) {
        (used.toFloat() / provider.total.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val percent = if (provider.total > 0) "${(progress * 100).roundToInt()}%" else "--"

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = provider.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            UsageBadge(percent)
        }

        TrafficDetailPanel(
            progress = progress,
            used = FormatUtils.formatBytes(used),
            remaining = FormatUtils.formatBytes(remaining),
            total = FormatUtils.formatBytes(provider.total),
            expire = formatExpire(provider.expire),
        )
    }
}

@Composable
private fun TrafficDetailPanel(
    progress: Float,
    used: String,
    remaining: String,
    total: String,
    expire: String,
) {
    val colorScheme = MiuixTheme.colorScheme
    val panelColor = if (colorScheme.background.luminance() < 0.5f) {
        colorScheme.surfaceContainerHighest
    } else {
        Color(0xFFF4F4F4)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth(),
        cornerRadius = 12.dp,
        insideMargin = PaddingValues(
            start = 12.dp,
            top = 12.dp,
            end = 12.dp,
            bottom = 14.dp,
        ),
        colors = CardDefaults.defaultColors(
            color = panelColor,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TrafficProgressBar(progress)
            UsedRemainingRow(
                used = used,
                remaining = remaining,
            )
            TotalExpireRow(
                total = total,
                expire = expire,
            )
        }
    }
}

@Composable
private fun UsedRemainingRow(
    used: String,
    remaining: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrafficMetaLabel(stringResource(Res.string.home_used))
            TrafficMetaValue(used)
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(20.dp)
                .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrafficMetaLabel(stringResource(Res.string.home_remaining))
            TrafficMetaValue(remaining)
        }
    }
}

@Composable
private fun TotalExpireRow(
    total: String,
    expire: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${stringResource(Res.string.home_total)} $total",
            modifier = Modifier.weight(0.42f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = "${stringResource(Res.string.home_expire)} $expire",
            modifier = Modifier.weight(0.58f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun TrafficMetaLabel(text: String) {
    Text(
        text = text,
        maxLines = 1,
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
private fun TrafficMetaValue(text: String) {
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.End,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurface,
    )
}

@Composable
private fun UsageBadge(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .squircleBackground(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f), 6.dp)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.primary,
    )
}

@Composable
private fun TrafficProgressBar(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(7.dp)
            .clip(RoundedCornerShape(50))
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .height(7.dp)
                .clip(RoundedCornerShape(50))
                .background(MiuixTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun formatExpire(expire: Long): String {
    if (expire <= 0) return stringResource(Res.string.home_no_expire)
    return Instant.fromEpochSeconds(expire)
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .formatDateTime()
}

private fun LocalDateTime.formatDateTime(): String = buildString {
    append(year.toString().padStart(4, '0'))
    append('-')
    append(month.number.toString().padStart(2, '0'))
    append('-')
    append(day.toString().padStart(2, '0'))
    append(' ')
    append(hour.toString().padStart(2, '0'))
    append(':')
    append(minute.toString().padStart(2, '0'))
}
