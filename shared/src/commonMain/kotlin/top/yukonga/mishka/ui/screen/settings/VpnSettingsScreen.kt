package top.yukonga.mishka.ui.screen.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.common_back
import mishka.shared.generated.resources.vpn_allow_bypass
import mishka.shared.generated.resources.vpn_allow_bypass_summary
import mishka.shared.generated.resources.vpn_allow_ipv6
import mishka.shared.generated.resources.vpn_allow_ipv6_summary
import mishka.shared.generated.resources.vpn_bypass_private
import mishka.shared.generated.resources.vpn_bypass_private_summary
import mishka.shared.generated.resources.vpn_dns_hijacking
import mishka.shared.generated.resources.vpn_dns_hijacking_summary
import mishka.shared.generated.resources.vpn_settings_title
import mishka.shared.generated.resources.vpn_system_proxy
import mishka.shared.generated.resources.vpn_system_proxy_summary
import mishka.shared.generated.resources.vpn_tunnel
import org.jetbrains.compose.resources.stringResource
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
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

@Composable
fun VpnSettingsScreen(
    storage: PlatformStorage,
    isSystemProxySupported: Boolean = false,
    onBack: () -> Unit = {},
) {
    val scrollBehavior = MiuixScrollBehavior()

    var bypassPrivate by remember { mutableStateOf(storage.getString(StorageKeys.VPN_BYPASS_PRIVATE_NETWORK, "true") == "true") }
    var allowBypass by remember { mutableStateOf(storage.getString(StorageKeys.VPN_ALLOW_BYPASS, "true") == "true") }
    var dnsHijacking by remember { mutableStateOf(storage.getString(StorageKeys.VPN_DNS_HIJACKING, "true") != "false") }
    var systemProxy by remember { mutableStateOf(storage.getString(StorageKeys.VPN_SYSTEM_PROXY, "true") != "false") }
    var allowIpv6 by remember { mutableStateOf(storage.getString(StorageKeys.VPN_ALLOW_IPV6, "false") == "true") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(Res.string.vpn_settings_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        val layoutDirection = LocalLayoutDirection.current
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(Res.string.common_back),
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
            ),
        ) {
            item { SmallTitle(text = stringResource(Res.string.vpn_tunnel)) }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                ) {
                    SwitchPreference(
                        title = stringResource(Res.string.vpn_bypass_private),
                        summary = stringResource(Res.string.vpn_bypass_private_summary),
                        checked = bypassPrivate,
                        onCheckedChange = {
                            bypassPrivate = it
                            storage.putString(StorageKeys.VPN_BYPASS_PRIVATE_NETWORK, it.toString())
                        },
                    )
                    SwitchPreference(
                        title = stringResource(Res.string.vpn_allow_bypass),
                        summary = stringResource(Res.string.vpn_allow_bypass_summary),
                        checked = allowBypass,
                        onCheckedChange = {
                            allowBypass = it
                            storage.putString(StorageKeys.VPN_ALLOW_BYPASS, it.toString())
                        },
                    )
                    SwitchPreference(
                        title = stringResource(Res.string.vpn_dns_hijacking),
                        summary = stringResource(Res.string.vpn_dns_hijacking_summary),
                        checked = dnsHijacking,
                        onCheckedChange = {
                            dnsHijacking = it
                            storage.putString(StorageKeys.VPN_DNS_HIJACKING, it.toString())
                        },
                    )
                    if (isSystemProxySupported) {
                        SwitchPreference(
                            title = stringResource(Res.string.vpn_system_proxy),
                            summary = stringResource(Res.string.vpn_system_proxy_summary),
                            checked = systemProxy,
                            onCheckedChange = {
                                systemProxy = it
                                storage.putString(StorageKeys.VPN_SYSTEM_PROXY, it.toString())
                            },
                        )
                    }
                    SwitchPreference(
                        title = stringResource(Res.string.vpn_allow_ipv6),
                        summary = stringResource(Res.string.vpn_allow_ipv6_summary),
                        checked = allowIpv6,
                        onCheckedChange = {
                            allowIpv6 = it
                            storage.putString(StorageKeys.VPN_ALLOW_IPV6, it.toString())
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
        }
    }
}
