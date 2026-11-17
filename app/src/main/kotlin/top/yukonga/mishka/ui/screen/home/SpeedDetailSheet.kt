package top.yukonga.mishka.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.collections.immutable.ImmutableList
import top.yukonga.mishka.R
import top.yukonga.mishka.ui.component.blur.LocalPageBackdrop
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.ui.util.sheetContentSafePadding
import top.yukonga.mishka.ui.util.sheetHeightTransition
import top.yukonga.mishka.util.FormatUtils
import top.yukonga.mishka.viewmodel.ConnectionRate
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 速度卡详情：当前跑得最快的几条连接，回答「这几 MB/s 是谁在跑」。速率的来历见 [ConnectionRate]。
 *
 * @param rates null 表示首轮差分未完成（需要两次快照），空表表示确实没有活跃连接
 */
@Composable
internal fun SpeedDetailSheet(
    show: Boolean,
    rates: ImmutableList<ConnectionRate>?,
    onDismiss: () -> Unit,
) {
    GlassBottomSheet(
        show = show,
        backdrop = LocalPageBackdrop.current,
        title = stringResource(R.string.home_speed_detail),
        onDismissRequest = onDismiss,
        endAction = {
            val dismiss = LocalDismissState.current
            IconButton(onClick = { dismiss?.invoke() }) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = stringResource(R.string.common_close),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .sheetContentSafePadding()
                .heightIn(min = 200.dp)
                .sheetHeightTransition(),
        ) {
            when {
                rates == null -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )

                rates.isEmpty() -> Text(
                    text = stringResource(R.string.home_speed_no_active_connection),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items = rates, key = { it.id }) { rate ->
                        // 排名逐秒变动，让重排走动画而不是硬跳
                        ConnectionRateCard(rate, Modifier.animateItem())
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionRateCard(rate: ConnectionRate, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 12.dp,
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.04f),
        ),
    ) {
        Text(
            text = rate.host,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (rate.node.isNotEmpty()) {
                Text(
                    text = rate.node,
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.width(12.dp))
            RateLabel("↑", rate.uploadRate, StatusColors.trafficUpload)
            Spacer(Modifier.width(10.dp))
            RateLabel("↓", rate.downloadRate, StatusColors.trafficDownload)
        }
    }
}

@Composable
private fun RateLabel(arrow: String, rate: Long, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = arrow,
            fontSize = 12.sp,
            color = color,
        )
        Text(
            text = FormatUtils.formatSpeed(rate),
            modifier = Modifier.padding(start = 2.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}
