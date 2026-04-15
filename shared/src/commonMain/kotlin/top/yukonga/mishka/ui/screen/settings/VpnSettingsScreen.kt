package top.yukonga.mishka.ui.screen.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.mishka.platform.PlatformStorage

@Composable
fun VpnSettingsScreen(
    storage: PlatformStorage,
    isSystemProxySupported: Boolean = false,
    onBack: () -> Unit = {},
    bottomPadding: Dp = 0.dp,
) {
    val scrollBehavior = MiuixScrollBehavior()

    var bypassPrivate by remember { mutableStateOf(storage.getString("vpn_bypass_private_network", "true") == "true") }
    var allowBypass by remember { mutableStateOf(storage.getString("vpn_allow_bypass", "true") == "true") }
    var dnsHijacking by remember { mutableStateOf(storage.getString("vpn_dns_hijacking", "true") != "false") }
    var systemProxy by remember { mutableStateOf(storage.getString("vpn_system_proxy", "true") != "false") }
    var allowIpv6 by remember { mutableStateOf(storage.getString("vpn_allow_ipv6", "false") == "true") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "VPN 设置",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        val layoutDirection = LocalLayoutDirection.current
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.graphicsLayer {
                                scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                            },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = bottomPadding,
            ),
        ) {
            item { SmallTitle(text = "VPN 隧道") }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                ) {
                    SwitchPreference(
                        title = "绕过私有网络",
                        summary = "排除局域网地址，不通过 VPN 路由",
                        checked = bypassPrivate,
                        onCheckedChange = {
                            bypassPrivate = it
                            storage.putString("vpn_bypass_private_network", it.toString())
                        },
                    )
                    SwitchPreference(
                        title = "允许应用绕过",
                        summary = "允许应用请求绕过 VPN 连接",
                        checked = allowBypass,
                        onCheckedChange = {
                            allowBypass = it
                            storage.putString("vpn_allow_bypass", it.toString())
                        },
                    )
                    SwitchPreference(
                        title = "DNS 劫持",
                        summary = "接管所有 DNS 请求",
                        checked = dnsHijacking,
                        onCheckedChange = {
                            dnsHijacking = it
                            storage.putString("vpn_dns_hijacking", it.toString())
                        },
                    )
                    if (isSystemProxySupported) {
                        SwitchPreference(
                            title = "系统代理",
                            summary = "配置系统级 HTTP 代理",
                            checked = systemProxy,
                            onCheckedChange = {
                                systemProxy = it
                                storage.putString("vpn_system_proxy", it.toString())
                            },
                        )
                    }
                    SwitchPreference(
                        title = "允许 IPv6",
                        summary = "通过 VPN 路由 IPv6 流量",
                        checked = allowIpv6,
                        onCheckedChange = {
                            allowIpv6 = it
                            storage.putString("vpn_allow_ipv6", it.toString())
                        },
                    )
                }
            }
        }
    }
}
