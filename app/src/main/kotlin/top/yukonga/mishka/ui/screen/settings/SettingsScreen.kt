package top.yukonga.mishka.ui.screen.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.mishka.BuildConfig
import top.yukonga.mishka.R
import top.yukonga.mishka.platform.BootStartManager
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.CardItem
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.groupedCardItems
import top.yukonga.mishka.ui.util.WideContentBox
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    onNavigateVpnSettings: () -> Unit = {},
    onNavigateRootSettings: () -> Unit = {},
    onNavigateNetworkSettings: () -> Unit = {},
    onNavigateMetaSettings: () -> Unit = {},
    onNavigateExternalControl: () -> Unit = {},
    onNavigateAutomation: () -> Unit = {},
    onNavigateAppProxy: () -> Unit = {},
    onNavigateWifiPolicy: () -> Unit = {},
    onNavigateThemeSettings: () -> Unit = {},
    onNavigateFileManager: () -> Unit = {},
    onNavigateOverrides: () -> Unit = {},
    onNavigateBackup: () -> Unit = {},
    onNavigateAbout: () -> Unit = {},
    bootStartManager: BootStartManager? = null,
    storage: PlatformStorage? = null,
    onHideTaskCardChange: ((Boolean) -> Unit)? = null,
    hasRootPermission: Boolean = false,
    isProxyRunning: Boolean = false,
) {
    val scrollBehavior = MiuixScrollBehavior()
    var isAutoStartEnabled by remember {
        mutableStateOf(bootStartManager?.isEnabled() ?: false)
    }
    var isAutoConnectEnabled by remember {
        mutableStateOf(storage?.getString(StorageKeys.AUTO_CONNECT_ON_LAUNCH, "false") == "true")
    }
    var isDynamicNotificationEnabled by remember {
        mutableStateOf(storage?.getString(StorageKeys.DYNAMIC_NOTIFICATION, "true") != "false")
    }
    var isUpdateViaProxyEnabled by remember {
        mutableStateOf(storage?.getString(StorageKeys.SUBSCRIPTION_UPDATE_VIA_PROXY, "true") != "false")
    }
    var isRestartAfterUpdateEnabled by remember {
        mutableStateOf(storage?.getString(StorageKeys.RESTART_AFTER_PROFILE_UPDATE, "true") != "false")
    }
    var isHideTaskCardEnabled by remember {
        mutableStateOf(storage?.getString(StorageKeys.HIDE_TASK_CARD, "false") == "true")
    }
    var tunModeIndex by remember {
        mutableIntStateOf(
            when (storage?.getString(StorageKeys.TUN_MODE, "vpn")) {
                "root_tun" -> 1
                "root_tproxy" -> 2
                else -> 0
            }
        )
    }

    val tunModeItems = listOf(
        stringResource(R.string.settings_tun_mode_vpn),
        stringResource(R.string.settings_tun_mode_root_tun),
        stringResource(R.string.settings_tun_mode_root_tproxy),
    )

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        modifier = modifier,
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.settings_title),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { innerPadding ->
        WideContentBox { sidePadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    start = sidePadding,
                    end = sidePadding,
                    bottom = bottomPadding,
                ),
            ) {
                item {
                    SmallTitle(text = stringResource(R.string.settings_network))
                }
                groupedCardItems(
                    keyPrefix = "settings_network",
                    items = buildList {
                        if (hasRootPermission) {
                            add(CardItem("tunMode") {
                                OverlayDropdownPreference(
                                    title = stringResource(R.string.settings_tun_mode),
                                    summary = when (tunModeIndex) {
                                        1 -> stringResource(R.string.settings_tun_root_tun_summary)
                                        2 -> stringResource(R.string.settings_tun_root_tproxy_summary)
                                        else -> stringResource(R.string.settings_tun_vpn_summary)
                                    },
                                    items = tunModeItems,
                                    selectedIndex = tunModeIndex,
                                    onSelectedIndexChange = { index ->
                                        val mode = when (index) {
                                            1 -> "root_tun"
                                            2 -> "root_tproxy"
                                            else -> "vpn"
                                        }
                                        storage?.putString(StorageKeys.TUN_MODE, mode)
                                        tunModeIndex = index
                                    },
                                    enabled = !isProxyRunning,
                                )
                            })
                        }
                        if (tunModeIndex == 0) {
                            add(CardItem("vpnSettings") {
                                ArrowPreference(
                                    title = stringResource(R.string.settings_vpn_settings),
                                    summary = stringResource(R.string.settings_vpn_summary),
                                    onClick = onNavigateVpnSettings,
                                )
                            })
                        }
                        if (tunModeIndex == 1 || tunModeIndex == 2) {
                            add(CardItem("rootSettings") {
                                ArrowPreference(
                                    title = stringResource(R.string.root_settings_title),
                                    summary = stringResource(R.string.root_settings_summary),
                                    onClick = onNavigateRootSettings,
                                )
                            })
                        }
                        add(CardItem("override") {
                            ArrowPreference(
                                title = stringResource(R.string.settings_override_settings),
                                summary = stringResource(R.string.settings_override_summary),
                                onClick = onNavigateNetworkSettings,
                            )
                        })
                        add(CardItem("meta") {
                            ArrowPreference(
                                title = stringResource(R.string.settings_meta_settings),
                                summary = stringResource(R.string.settings_meta_summary),
                                onClick = onNavigateMetaSettings,
                            )
                        })
                        add(CardItem("externalControl") {
                            ArrowPreference(
                                title = stringResource(R.string.external_control_title),
                                summary = stringResource(R.string.settings_external_control_summary),
                                onClick = onNavigateExternalControl,
                            )
                        })
                        add(CardItem("automation") {
                            ArrowPreference(
                                title = stringResource(R.string.automation_title),
                                summary = stringResource(R.string.settings_automation_summary),
                                onClick = onNavigateAutomation,
                            )
                        })
                        add(CardItem("appProxy") {
                            ArrowPreference(
                                title = stringResource(R.string.settings_app_proxy),
                                summary = stringResource(R.string.settings_app_proxy_summary),
                                onClick = onNavigateAppProxy,
                            )
                        })
                        add(CardItem("wifiPolicy") {
                            ArrowPreference(
                                title = stringResource(R.string.settings_wifi_policy),
                                summary = stringResource(R.string.settings_wifi_policy_summary),
                                onClick = onNavigateWifiPolicy,
                            )
                        })
                        add(CardItem("fileManager") {
                            ArrowPreference(
                                title = stringResource(R.string.settings_file_manager),
                                summary = stringResource(R.string.settings_file_manager_summary),
                                onClick = onNavigateFileManager,
                            )
                        })
                        add(CardItem("overrides") {
                            ArrowPreference(
                                title = stringResource(R.string.settings_overrides),
                                summary = stringResource(R.string.settings_overrides_summary),
                                onClick = onNavigateOverrides,
                            )
                        })
                        add(CardItem("backup") {
                            ArrowPreference(
                                title = stringResource(R.string.settings_backup),
                                summary = stringResource(R.string.settings_backup_summary),
                                onClick = onNavigateBackup,
                            )
                        })
                    },
                )
                item {
                    SmallTitle(text = stringResource(R.string.settings_general))
                }
                groupedCardItems(
                    keyPrefix = "settings_general",
                    outerBottomPadding = 12.dp,
                    items = buildList {
                        if (onHideTaskCardChange != null) {
                            add(CardItem("hideTaskCard") {
                                SwitchPreference(
                                    title = stringResource(R.string.settings_hide_task_card),
                                    summary = stringResource(R.string.settings_hide_task_card_summary),
                                    checked = isHideTaskCardEnabled,
                                    onCheckedChange = { checked ->
                                        storage?.putString(StorageKeys.HIDE_TASK_CARD, if (checked) "true" else "false")
                                        isHideTaskCardEnabled = checked
                                        onHideTaskCardChange(checked)
                                    },
                                )
                            })
                        }
                        add(CardItem("dynamicNotification") {
                            val isVpnMode = tunModeIndex == 0
                            SwitchPreference(
                                title = stringResource(R.string.settings_dynamic_notification),
                                summary = stringResource(
                                    if (isVpnMode) R.string.settings_dynamic_notification_summary
                                    else R.string.settings_dynamic_notification_summary_root_unsupported
                                ),
                                checked = isDynamicNotificationEnabled && isVpnMode,
                                enabled = isVpnMode,
                                onCheckedChange = { checked ->
                                    storage?.putString(StorageKeys.DYNAMIC_NOTIFICATION, if (checked) "true" else "false")
                                    isDynamicNotificationEnabled = checked
                                    ProxyServiceBridge.requestNotificationRefresh()
                                },
                            )
                        })
                        add(CardItem("subscriptionViaProxy") {
                            SwitchPreference(
                                title = stringResource(R.string.settings_subscription_via_proxy),
                                summary = stringResource(R.string.settings_subscription_via_proxy_summary),
                                checked = isUpdateViaProxyEnabled,
                                onCheckedChange = { checked ->
                                    storage?.putString(StorageKeys.SUBSCRIPTION_UPDATE_VIA_PROXY, if (checked) "true" else "false")
                                    isUpdateViaProxyEnabled = checked
                                },
                            )
                        })
                        add(CardItem("restartAfterUpdate") {
                            SwitchPreference(
                                title = stringResource(R.string.settings_restart_after_update),
                                summary = stringResource(R.string.settings_restart_after_update_summary),
                                checked = isRestartAfterUpdateEnabled,
                                onCheckedChange = { checked ->
                                    storage?.putString(
                                        StorageKeys.RESTART_AFTER_PROFILE_UPDATE,
                                        if (checked) "true" else "false",
                                    )
                                    isRestartAfterUpdateEnabled = checked
                                },
                            )
                        })
                        add(CardItem("autoConnect") {
                            SwitchPreference(
                                title = stringResource(R.string.settings_auto_connect),
                                summary = stringResource(R.string.settings_auto_connect_summary),
                                checked = isAutoConnectEnabled,
                                onCheckedChange = { checked ->
                                    storage?.putString(StorageKeys.AUTO_CONNECT_ON_LAUNCH, if (checked) "true" else "false")
                                    isAutoConnectEnabled = checked
                                },
                            )
                        })
                        if (bootStartManager != null) {
                            add(CardItem("autoRestart") {
                                SwitchPreference(
                                    title = stringResource(R.string.settings_auto_restart),
                                    summary = stringResource(R.string.settings_auto_restart_summary),
                                    checked = isAutoStartEnabled,
                                    onCheckedChange = { checked ->
                                        bootStartManager.setEnabled(checked)
                                        isAutoStartEnabled = checked
                                    },
                                )
                            })
                        }
                        add(CardItem("theme") {
                            ArrowPreference(
                                title = stringResource(R.string.settings_theme_title),
                                summary = stringResource(R.string.settings_theme_summary),
                                onClick = onNavigateThemeSettings,
                            )
                        })
                        add(CardItem("about") {
                            ArrowPreference(
                                title = stringResource(R.string.settings_about),
                                summary = "Mishka v${BuildConfig.VERSION_NAME}",
                                onClick = onNavigateAbout,
                            )
                        })
                    },
                )
            }
        }
    }
}
