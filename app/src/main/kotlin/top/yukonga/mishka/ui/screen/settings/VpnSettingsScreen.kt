package top.yukonga.mishka.ui.screen.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.mishka.R
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.BackNavigationIcon
import top.yukonga.mishka.ui.component.CardItem
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.groupedCardItems
import top.yukonga.mishka.ui.component.overScrollVertical
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.SwitchPreference
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

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.vpn_settings_title),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = { BackNavigationIcon(onBack) },
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .horizontalCutoutPadding()
                .scrollEndHaptic()
                .overScrollVertical(scrollBehavior)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
            ),
        ) {
            item { SmallTitle(text = stringResource(R.string.vpn_tunnel)) }
            groupedCardItems(
                keyPrefix = "vpn_tunnel",
                outerBottomPadding = 0.dp,
                items = buildList {
                    add(CardItem("bypassPrivate") {
                        SwitchPreference(
                            title = stringResource(R.string.vpn_bypass_private),
                            summary = stringResource(R.string.vpn_bypass_private_summary),
                            checked = bypassPrivate,
                            onCheckedChange = {
                                bypassPrivate = it
                                storage.putString(StorageKeys.VPN_BYPASS_PRIVATE_NETWORK, it.toString())
                            },
                        )
                    })
                    add(CardItem("allowBypass") {
                        SwitchPreference(
                            title = stringResource(R.string.vpn_allow_bypass),
                            summary = stringResource(R.string.vpn_allow_bypass_summary),
                            checked = allowBypass,
                            onCheckedChange = {
                                allowBypass = it
                                storage.putString(StorageKeys.VPN_ALLOW_BYPASS, it.toString())
                            },
                        )
                    })
                    add(CardItem("dnsHijacking") {
                        SwitchPreference(
                            title = stringResource(R.string.vpn_dns_hijacking),
                            summary = stringResource(R.string.vpn_dns_hijacking_summary),
                            checked = dnsHijacking,
                            onCheckedChange = {
                                dnsHijacking = it
                                storage.putString(StorageKeys.VPN_DNS_HIJACKING, it.toString())
                            },
                        )
                    })
                    if (isSystemProxySupported) {
                        add(CardItem("systemProxy") {
                            SwitchPreference(
                                title = stringResource(R.string.vpn_system_proxy),
                                summary = stringResource(R.string.vpn_system_proxy_summary),
                                checked = systemProxy,
                                onCheckedChange = {
                                    systemProxy = it
                                    storage.putString(StorageKeys.VPN_SYSTEM_PROXY, it.toString())
                                },
                            )
                        })
                    }
                    add(CardItem("allowIpv6") {
                        SwitchPreference(
                            title = stringResource(R.string.vpn_allow_ipv6),
                            summary = stringResource(R.string.vpn_allow_ipv6_summary),
                            checked = allowIpv6,
                            onCheckedChange = {
                                allowIpv6 = it
                                storage.putString(StorageKeys.VPN_ALLOW_IPV6, it.toString())
                            },
                        )
                    })
                },
            )
            item {
                Spacer(
                    Modifier
                        .height(24.dp)
                        .navigationBarsPadding()
                )
            }
        }
    }
}
