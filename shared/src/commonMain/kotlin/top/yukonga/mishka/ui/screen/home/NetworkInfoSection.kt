package top.yukonga.mishka.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.mishka.viewmodel.HomeUiState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.miuixShape

fun LazyListScope.networkInfoSection(state: HomeUiState = HomeUiState()) {
    item(key = "network_title") {
        SmallTitle(text = "网络")
    }
    item(key = "network") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 6.dp)
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                insideMargin = PaddingValues(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "IP",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    BadgeLabel("LAN")
                }
                InfoRow("地址", state.localIp, Modifier.padding(top = 8.dp))
                InfoRow("接口", state.interfaceName, Modifier.padding(top = 4.dp))
            }
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                insideMargin = PaddingValues(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "网速",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    BadgeLabel("NET")
                }
                InfoRow("上行", state.uploadSpeed, Modifier.padding(top = 8.dp))
                InfoRow("下行", state.downloadSpeed, Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun BadgeLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .clip(miuixShape(6.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.primary,
    )
}

@Composable
private fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
    }
}
