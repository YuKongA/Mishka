package top.yukonga.mishka.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.rememberTextFieldState
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
import mishka.shared.generated.resources.common_cancel
import mishka.shared.generated.resources.common_confirm
import mishka.shared.generated.resources.network_input_value
import mishka.shared.generated.resources.root_section_device
import mishka.shared.generated.resources.root_section_tether
import mishka.shared.generated.resources.root_settings_title
import mishka.shared.generated.resources.root_tether_ifaces_title
import mishka.shared.generated.resources.root_tether_mode_bypass
import mishka.shared.generated.resources.root_tether_mode_proxy
import mishka.shared.generated.resources.root_tether_mode_title
import mishka.shared.generated.resources.settings_tun_device
import org.jetbrains.compose.resources.stringResource
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.ui.component.RestartRequiredHint
import top.yukonga.mishka.ui.component.TetherInterfaceEditDialog
import top.yukonga.mishka.ui.component.tetherInterfaceSummary
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * ROOT 模式专属设置页。仅在 ROOT 模式下可见。
 *
 * 内容：
 * - TUN 设备名称（mihomo 创建的 TUN 接口名，代理运行时不可改）
 * - 热点客户端流量处置（绕过代理 / 走代理）
 * - 热点接口名列表（手填 + 扫描辅助）
 */
@Composable
fun RootSettingsScreen(
    storage: PlatformStorage,
    isProxyRunning: Boolean,
    onBack: () -> Unit = {},
) {
    val scrollBehavior = MiuixScrollBehavior()

    var tunDevice by remember {
        mutableStateOf(storage.getString(StorageKeys.ROOT_TUN_DEVICE, DEFAULT_TUN_DEVICE))
    }
    var showTunDeviceDialog by remember { mutableStateOf(false) }
    val tunDeviceTextState = rememberTextFieldState()

    val bypassLabel = stringResource(Res.string.root_tether_mode_bypass)
    val proxyLabel = stringResource(Res.string.root_tether_mode_proxy)
    val tetherModeValues = listOf("bypass", "proxy")
    var tetherMode by remember { mutableStateOf(storage.getString(StorageKeys.ROOT_TETHER_MODE, "bypass")) }
    var tetherIfaces by remember {
        mutableStateOf(storage.getString(StorageKeys.ROOT_TETHER_IFACES, DEFAULT_TETHER_IFACES))
    }
    var showTetherDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(Res.string.root_settings_title),
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
            item { RestartRequiredHint() }
            item { SmallTitle(text = stringResource(Res.string.root_section_device)) }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
                ) {
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
            }
            item { SmallTitle(text = stringResource(Res.string.root_section_tether)) }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
                ) {
                    val tetherItems = listOf(bypassLabel, proxyLabel)
                    val selectedIndex = tetherModeValues.indexOf(tetherMode).coerceAtLeast(0)
                    OverlayDropdownPreference(
                        title = stringResource(Res.string.root_tether_mode_title),
                        summary = tetherItems[selectedIndex],
                        items = tetherItems,
                        selectedIndex = selectedIndex,
                        onSelectedIndexChange = { index ->
                            val value = tetherModeValues[index]
                            tetherMode = value
                            storage.putString(StorageKeys.ROOT_TETHER_MODE, value)
                        },
                    )
                    ArrowPreference(
                        title = stringResource(Res.string.root_tether_ifaces_title),
                        summary = tetherInterfaceSummary(tetherIfaces),
                        onClick = { showTetherDialog = true },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
        }
    }

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
                    val value = tunDeviceTextState.text.toString().trim().ifEmpty { DEFAULT_TUN_DEVICE }
                    storage.putString(StorageKeys.ROOT_TUN_DEVICE, value)
                    tunDevice = value
                    showTunDeviceDialog = false
                },
            )
        }
    }

    TetherInterfaceEditDialog(
        show = showTetherDialog,
        title = stringResource(Res.string.root_tether_ifaces_title),
        initialValue = tetherIfaces,
        onDismiss = { showTetherDialog = false },
        onConfirm = { csv ->
            tetherIfaces = csv
            storage.putString(StorageKeys.ROOT_TETHER_IFACES, csv)
        },
    )
}

private const val DEFAULT_TUN_DEVICE = "Mishka"

private const val DEFAULT_TETHER_IFACES = "wlan1,wlan2"
