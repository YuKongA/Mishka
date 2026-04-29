package top.yukonga.mishka.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.home_address
import mishka.shared.generated.resources.home_download
import mishka.shared.generated.resources.home_interface
import mishka.shared.generated.resources.home_network
import mishka.shared.generated.resources.home_speed
import mishka.shared.generated.resources.home_upload
import org.jetbrains.compose.resources.stringResource
import top.yukonga.mishka.viewmodel.SpeedSnapshot
import top.yukonga.mishka.viewmodel.SystemInfoSnapshot
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

fun LazyListScope.networkInfoSection(
    speed: SpeedSnapshot = SpeedSnapshot(),
    systemInfo: SystemInfoSnapshot = SystemInfoSnapshot(),
) {
    item(key = "network_title") {
        SmallTitle(text = stringResource(Res.string.home_network))
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
                    BadgeLabel(ipCategoryBadge(systemInfo.localIp))
                }
                InfoRow(stringResource(Res.string.home_address), systemInfo.localIp, Modifier.padding(top = 8.dp))
                InfoRow(stringResource(Res.string.home_interface), systemInfo.interfaceName, Modifier.padding(top = 4.dp))
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
                        text = stringResource(Res.string.home_speed),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    BadgeLabel("NET")
                }
                InfoRow(stringResource(Res.string.home_upload), speed.uploadSpeed, Modifier.padding(top = 8.dp))
                InfoRow(stringResource(Res.string.home_download), speed.downloadSpeed, Modifier.padding(top = 4.dp))
            }
        }
    }
}

/**
 * 按 IP 段归类：
 * - `198.18.0.0/15` → TUN（sing-tun / VpnService fake-ip 段）
 * - RFC1918 `10/8` / `172.16/12` / `192.168/16` + CGNAT `100.64/10` + link-local `169.254/16` → LAN
 * - 其他（公网 / 空值 / 解析失败）→ WAN
 */
private fun ipCategoryBadge(ip: String): String {
    val parts = ip.split('.')
    if (parts.size != 4) return "WAN"
    val o1 = parts[0].toIntOrNull() ?: return "WAN"
    val o2 = parts[1].toIntOrNull() ?: return "WAN"
    return when (o1) {
        198 if (o2 == 18 || o2 == 19) -> "TUN"
        10 -> "LAN"
        192 if o2 == 168 -> "LAN"
        172 if o2 in 16..31 -> "LAN"
        100 if o2 in 64..127 -> "LAN"
        169 if o2 == 254 -> "LAN"
        else -> "WAN"
    }
}

