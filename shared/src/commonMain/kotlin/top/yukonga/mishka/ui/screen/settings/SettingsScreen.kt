package top.yukonga.mishka.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.common_cancel
import mishka.shared.generated.resources.common_confirm
import mishka.shared.generated.resources.external_control_title
import mishka.shared.generated.resources.network_input_value
import mishka.shared.generated.resources.settings_about
import mishka.shared.generated.resources.settings_app_proxy
import mishka.shared.generated.resources.settings_app_proxy_summary
import mishka.shared.generated.resources.settings_auto_restart
import mishka.shared.generated.resources.settings_auto_restart_summary
import mishka.shared.generated.resources.settings_dynamic_notification
import mishka.shared.generated.resources.settings_dynamic_notification_summary
import mishka.shared.generated.resources.settings_external_control_summary
import mishka.shared.generated.resources.settings_file_manager
import mishka.shared.generated.resources.settings_file_manager_summary
import mishka.shared.generated.resources.settings_general
import mishka.shared.generated.resources.settings_meta_settings
import mishka.shared.generated.resources.settings_meta_summary
import mishka.shared.generated.resources.settings_network
import mishka.shared.generated.resources.settings_override_settings
import mishka.shared.generated.resources.settings_override_summary
import mishka.shared.generated.resources.settings_predictive_back
import mishka.shared.generated.resources.settings_predictive_back_summary
import mishka.shared.generated.resources.settings_subscription_via_proxy
import mishka.shared.generated.resources.settings_subscription_via_proxy_summary
import mishka.shared.generated.resources.settings_theme_dark
import mishka.shared.generated.resources.settings_theme_light
import mishka.shared.generated.resources.settings_theme_mode
import mishka.shared.generated.resources.settings_theme_system
import mishka.shared.generated.resources.settings_title
import mishka.shared.generated.resources.settings_tun_device
import mishka.shared.generated.resources.settings_tun_mode
import mishka.shared.generated.resources.settings_tun_root_summary
import mishka.shared.generated.resources.settings_tun_vpn_summary
import mishka.shared.generated.resources.settings_vpn_settings
import mishka.shared.generated.resources.settings_vpn_summary
import org.jetbrains.compose.resources.stringResource
import top.yukonga.mishka.platform.BootStartManager
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    onNavigateVpnSettings: () -> Unit = {},
    onNavigateNetworkSettings: () -> Unit = {},
    onNavigateMetaSettings: () -> Unit = {},
    onNavigateExternalControl: () -> Unit = {},
    onNavigateAppProxy: () -> Unit = {},
    onNavigateFileManager: () -> Unit = {},
    onNavigateAbout: () -> Unit = {},
    bootStartManager: BootStartManager? = null,
    colorMode: Int = 0,
    onColorModeChange: (Int) -> Unit = {},
    storage: PlatformStorage? = null,
    onPredictiveBackChange: ((Boolean) -> Unit)? = null,
    hasRootPermission: Boolean = false,
    isProxyRunning: Boolean = false,
) {
    val scrollBehavior = MiuixScrollBehavior()
    var isAutoStartEnabled by remember {
        mutableStateOf(bootStartManager?.isEnabled() ?: false)
    }
    var isPredictiveBackEnabled by remember {
        mutableStateOf(storage?.getString(StorageKeys.PREDICTIVE_BACK, "false") == "true")
    }
    var isDynamicNotificationEnabled by remember {
        mutableStateOf(storage?.getString(StorageKeys.DYNAMIC_NOTIFICATION, "true") != "false")
    }
    var isUpdateViaProxyEnabled by remember {
        mutableStateOf(storage?.getString(StorageKeys.SUBSCRIPTION_UPDATE_VIA_PROXY, "true") != "false")
    }
    var tunModeIndex by remember {
        mutableIntStateOf(if (storage?.getString(StorageKeys.TUN_MODE, "vpn") == "root") 1 else 0)
    }
    var tunDevice by remember {
        mutableStateOf(storage?.getString(StorageKeys.ROOT_TUN_DEVICE, "Mishka") ?: "Mishka")
    }
    var showTunDeviceDialog by remember { mutableStateOf(false) }
    val tunDeviceTextState = rememberTextFieldState()

    val themeSystemStr = stringResource(Res.string.settings_theme_system)
    val themeLightStr = stringResource(Res.string.settings_theme_light)
    val themeDarkStr = stringResource(Res.string.settings_theme_dark)
    val themeItems = listOf(themeSystemStr, themeLightStr, themeDarkStr)
    val tunModeItems = listOf("VPN", "ROOT")

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = stringResource(Res.string.settings_title),
                scrollBehavior = scrollBehavior,
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
            item {
                SmallTitle(text = stringResource(Res.string.settings_network))
            }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 6.dp),
                ) {
                    if (hasRootPermission) {
                        OverlayDropdownPreference(
                            title = stringResource(Res.string.settings_tun_mode),
                            summary = if (tunModeIndex == 0) stringResource(Res.string.settings_tun_vpn_summary) else stringResource(Res.string.settings_tun_root_summary),
                            items = tunModeItems,
                            selectedIndex = tunModeIndex,
                            onSelectedIndexChange = { index ->
                                val mode = if (index == 1) "root" else "vpn"
                                storage?.putString(StorageKeys.TUN_MODE, mode)
                                tunModeIndex = index
                            },
                            enabled = !isProxyRunning,
                        )
                    }
                    if (tunModeIndex == 0) {
                        ArrowPreference(
                            title = stringResource(Res.string.settings_vpn_settings),
                            summary = stringResource(Res.string.settings_vpn_summary),
                            onClick = onNavigateVpnSettings,
                        )
                    }
                    if (tunModeIndex == 1) {
                        ArrowPreference(
                            title = stringResource(Res.string.settings_tun_device),
                            summary = tunDevice,
                            onClick = {
                                tunDeviceTextState.edit { replace(0, length, tunDevice) }
                                showTunDeviceDialog = true
                            },
                            enabled = !isProxyRunning,
                        )
                    }
                    ArrowPreference(
                        title = stringResource(Res.string.settings_override_settings),
                        summary = stringResource(Res.string.settings_override_summary),
                        onClick = onNavigateNetworkSettings,
                    )
                    ArrowPreference(
                        title = stringResource(Res.string.settings_meta_settings),
                        summary = stringResource(Res.string.settings_meta_summary),
                        onClick = onNavigateMetaSettings,
                    )
                    ArrowPreference(
                        title = stringResource(Res.string.external_control_title),
                        summary = stringResource(Res.string.settings_external_control_summary),
                        onClick = onNavigateExternalControl,
                    )
                    ArrowPreference(
                        title = stringResource(Res.string.settings_app_proxy),
                        summary = stringResource(Res.string.settings_app_proxy_summary),
                        onClick = onNavigateAppProxy,
                    )
                    ArrowPreference(
                        title = stringResource(Res.string.settings_file_manager),
                        summary = stringResource(Res.string.settings_file_manager_summary),
                        onClick = onNavigateFileManager,
                    )
                }
            }
            item {
                SmallTitle(text = stringResource(Res.string.settings_general))
            }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    SwitchPreference(
                        title = stringResource(Res.string.settings_dynamic_notification),
                        summary = stringResource(Res.string.settings_dynamic_notification_summary),
                        checked = isDynamicNotificationEnabled,
                        onCheckedChange = { checked ->
                            storage?.putString(StorageKeys.DYNAMIC_NOTIFICATION, if (checked) "true" else "false")
                            isDynamicNotificationEnabled = checked
                            ProxyServiceBridge.requestNotificationRefresh()
                        },
                    )
                    SwitchPreference(
                        title = stringResource(Res.string.settings_subscription_via_proxy),
                        summary = stringResource(Res.string.settings_subscription_via_proxy_summary),
                        checked = isUpdateViaProxyEnabled,
                        onCheckedChange = { checked ->
                            storage?.putString(StorageKeys.SUBSCRIPTION_UPDATE_VIA_PROXY, if (checked) "true" else "false")
                            isUpdateViaProxyEnabled = checked
                        },
                    )
                    if (bootStartManager != null) {
                        SwitchPreference(
                            title = stringResource(Res.string.settings_auto_restart),
                            summary = stringResource(Res.string.settings_auto_restart_summary),
                            checked = isAutoStartEnabled,
                            onCheckedChange = { checked ->
                                bootStartManager.setEnabled(checked)
                                isAutoStartEnabled = checked
                            },
                        )
                    }
                    OverlayDropdownPreference(
                        title = stringResource(Res.string.settings_theme_mode),
                        summary = themeItems.getOrElse(colorMode) { themeSystemStr },
                        items = themeItems,
                        selectedIndex = colorMode,
                        onSelectedIndexChange = { index ->
                            onColorModeChange(index)
                            val value = when (index) {
                                1 -> "light"
                                2 -> "dark"
                                else -> "system"
                            }
                            storage?.putString(StorageKeys.DARK_MODE, value)
                        },
                    )
                    if (onPredictiveBackChange != null) {
                        SwitchPreference(
                            title = stringResource(Res.string.settings_predictive_back),
                            summary = stringResource(Res.string.settings_predictive_back_summary),
                            checked = isPredictiveBackEnabled,
                            onCheckedChange = { checked ->
                                storage?.putString(StorageKeys.PREDICTIVE_BACK, if (checked) "true" else "false")
                                isPredictiveBackEnabled = checked
                                onPredictiveBackChange(checked)
                            },
                        )
                    }
                    ArrowPreference(
                        title = stringResource(Res.string.settings_about),
                        summary = "Mishka v${misc.VersionInfo.VERSION_NAME}",
                        onClick = onNavigateAbout,
                    )
                }
            }
        }
    }

    // TUN 设备名称编辑 Dialog
    WindowDialog(
        show = showTunDeviceDialog,
        title = stringResource(Res.string.settings_tun_device),
        onDismissRequest = { showTunDeviceDialog = false },
    ) {
        TextField(
            state = tunDeviceTextState,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(Res.string.network_input_value),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(Res.string.common_cancel),
                modifier = Modifier.weight(1f),
                onClick = { showTunDeviceDialog = false },
            )
            TextButton(
                text = stringResource(Res.string.common_confirm),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    val value = tunDeviceTextState.text.toString().trim().ifEmpty { "Mishka" }
                    storage?.putString(StorageKeys.ROOT_TUN_DEVICE, value)
                    tunDevice = value
                    showTunDeviceDialog = false
                },
            )
        }
    }
}
