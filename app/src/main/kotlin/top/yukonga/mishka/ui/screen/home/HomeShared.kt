package top.yukonga.mishka.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalHomeCardHorizontalPadding = staticCompositionLocalOf { PaddingValues(horizontal = 12.dp) }

/** 卡片背景水印的共享度量，供 [TrafficSparkline] 等背景绘制组件复用 */
internal const val WatermarkHeightFraction = 0.52f

internal const val WatermarkFillAlpha = 0.22f

internal val WatermarkStrokeWidth = 1.5.dp

@Composable
internal fun BadgeLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .squircleBackground(MiuixTheme.colorScheme.secondaryContainer, 6.dp)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.primary,
    )
}

@Composable
internal fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
    }
}
