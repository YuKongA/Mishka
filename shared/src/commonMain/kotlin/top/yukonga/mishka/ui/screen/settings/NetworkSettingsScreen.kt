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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.common_back
import mishka.shared.generated.resources.common_cancel
import mishka.shared.generated.resources.common_cleared
import mishka.shared.generated.resources.common_confirm
import mishka.shared.generated.resources.common_items_count
import mishka.shared.generated.resources.common_not_modified
import mishka.shared.generated.resources.network_allow_lan
import mishka.shared.generated.resources.network_bind_address
import mishka.shared.generated.resources.network_dns
import mishka.shared.generated.resources.network_dns_default_nameserver
import mishka.shared.generated.resources.network_dns_enable
import mishka.shared.generated.resources.network_dns_enhanced_mode
import mishka.shared.generated.resources.network_dns_fakeip_filter
import mishka.shared.generated.resources.network_dns_listen
import mishka.shared.generated.resources.network_dns_listen_title
import mishka.shared.generated.resources.network_dns_use_hosts
import mishka.shared.generated.resources.network_external_controller
import mishka.shared.generated.resources.network_http_port
import mishka.shared.generated.resources.network_input_value
import mishka.shared.generated.resources.network_log_level
import mishka.shared.generated.resources.network_mixed_port
import mishka.shared.generated.resources.network_options
import mishka.shared.generated.resources.network_port_label
import mishka.shared.generated.resources.network_port_zero_hint
import mishka.shared.generated.resources.network_proxy_ports
import mishka.shared.generated.resources.network_redir_port
import mishka.shared.generated.resources.network_settings_title
import mishka.shared.generated.resources.network_socks_port
import mishka.shared.generated.resources.network_tproxy_port
import org.jetbrains.compose.resources.stringResource
import top.yukonga.mishka.data.repository.OverrideStorageHelper
import top.yukonga.mishka.ui.component.ListEditDialog
import top.yukonga.mishka.ui.component.RestartRequiredHint
import top.yukonga.mishka.ui.component.TriStatePreference
import top.yukonga.mishka.viewmodel.OverrideSettingsViewModel
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

@Composable
fun NetworkSettingsScreen(
    viewModel: OverrideSettingsViewModel,
    onBack: () -> Unit = {},
    bottomPadding: Dp = 0.dp,
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollBehavior = MiuixScrollBehavior()

    // 端口编辑 Dialog 状态
    var showPortDialog by remember { mutableStateOf(false) }
    var editingPortKey by remember { mutableStateOf("") }
    var editingPortTitle by remember { mutableStateOf("") }
    val portTextState = rememberTextFieldState()

    // 字符串编辑 Dialog 状态
    var showStringDialog by remember { mutableStateOf(false) }
    var editingStringKey by remember { mutableStateOf("") }
    var editingStringTitle by remember { mutableStateOf("") }
    val stringTextState = rememberTextFieldState()

    // 列表编辑 Dialog 状态
    var showListDialog by remember { mutableStateOf(false) }
    var editingListKey by remember { mutableStateOf("") }
    var editingListTitle by remember { mutableStateOf("") }
    val listTextState = rememberTextFieldState()

    // 端口 item 点击辅助
    fun openPortDialog(title: String, key: String, value: Int?) {
        editingPortKey = key
        editingPortTitle = title
        portTextState.edit { replace(0, length, value?.toString() ?: "") }
        showPortDialog = true
    }

    // 字符串 item 点击辅助
    fun openStringDialog(title: String, key: String, value: String?) {
        editingStringKey = key
        editingStringTitle = title
        stringTextState.edit { replace(0, length, value ?: "") }
        showStringDialog = true
    }

    // 列表 item 点击辅助
    fun openListDialog(title: String, key: String, value: List<String>?) {
        editingListKey = key
        editingListTitle = title
        listTextState.edit { replace(0, length, value?.joinToString("\n") ?: "") }
        showListDialog = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(Res.string.network_settings_title),
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
                bottom = bottomPadding,
            ),
        ) {
            item { RestartRequiredHint() }

            // === 代理端口 ===
            item { SmallTitle(text = stringResource(Res.string.network_proxy_ports)) }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 6.dp),
                ) {
                    val httpPortTitle = stringResource(Res.string.network_http_port)
                    ArrowPreference(
                        title = httpPortTitle,
                        summary = portSummary(uiState.httpPort),
                        onClick = { openPortDialog(httpPortTitle, OverrideStorageHelper.KEY_HTTP_PORT, uiState.httpPort) },
                    )
                    val socksPortTitle = stringResource(Res.string.network_socks_port)
                    ArrowPreference(
                        title = socksPortTitle,
                        summary = portSummary(uiState.socksPort),
                        onClick = { openPortDialog(socksPortTitle, OverrideStorageHelper.KEY_SOCKS_PORT, uiState.socksPort) },
                    )
                    val redirPortTitle = stringResource(Res.string.network_redir_port)
                    ArrowPreference(
                        title = redirPortTitle,
                        summary = portSummary(uiState.redirPort),
                        onClick = { openPortDialog(redirPortTitle, OverrideStorageHelper.KEY_REDIR_PORT, uiState.redirPort) },
                    )
                    val tproxyPortTitle = stringResource(Res.string.network_tproxy_port)
                    ArrowPreference(
                        title = tproxyPortTitle,
                        summary = portSummary(uiState.tproxyPort),
                        onClick = { openPortDialog(tproxyPortTitle, OverrideStorageHelper.KEY_TPROXY_PORT, uiState.tproxyPort) },
                    )
                    val mixedPortTitle = stringResource(Res.string.network_mixed_port)
                    ArrowPreference(
                        title = mixedPortTitle,
                        summary = portSummary(uiState.mixedPort),
                        onClick = { openPortDialog(mixedPortTitle, OverrideStorageHelper.KEY_MIXED_PORT, uiState.mixedPort) },
                    )
                }
            }

            // === 网络选项 ===
            item { SmallTitle(text = stringResource(Res.string.network_options)) }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 6.dp),
                ) {
                    TriStatePreference(
                        title = stringResource(Res.string.network_allow_lan),
                        value = uiState.allowLan,
                        onValueChange = { viewModel.updateBoolean(OverrideStorageHelper.KEY_ALLOW_LAN, it) },
                    )
                    TriStatePreference(
                        title = "IPv6",
                        value = uiState.ipv6,
                        onValueChange = { viewModel.updateBoolean(OverrideStorageHelper.KEY_IPV6, it) },
                    )
                    val extControllerTitle = stringResource(Res.string.network_external_controller)
                    ArrowPreference(
                        title = extControllerTitle,
                        summary = uiState.externalController ?: stringResource(Res.string.common_not_modified),
                        onClick = { openStringDialog(extControllerTitle, OverrideStorageHelper.KEY_EXTERNAL_CONTROLLER, uiState.externalController) },
                    )
                    val bindAddrTitle = stringResource(Res.string.network_bind_address)
                    ArrowPreference(
                        title = bindAddrTitle,
                        summary = uiState.bindAddress ?: stringResource(Res.string.common_not_modified),
                        onClick = { openStringDialog(bindAddrTitle, OverrideStorageHelper.KEY_BIND_ADDRESS, uiState.bindAddress) },
                    )
                    LogLevelPreference(
                        value = uiState.logLevel,
                        onValueChange = { viewModel.updateString(OverrideStorageHelper.KEY_LOG_LEVEL, it) },
                    )
                }
            }

            // === DNS ===
            item { SmallTitle(text = stringResource(Res.string.network_dns)) }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 6.dp),
                ) {
                    TriStatePreference(
                        title = stringResource(Res.string.network_dns_enable),
                        value = uiState.dnsEnable,
                        onValueChange = { viewModel.updateBoolean(OverrideStorageHelper.KEY_DNS_ENABLE, it) },
                    )
                    // DNS 显式关闭时禁用子项；null（不修改）仍保留可编辑，允许用户预配置子覆写
                    val dnsSubEnabled = uiState.dnsEnable != false
                    val dnsListenTitle = stringResource(Res.string.network_dns_listen_title)
                    ArrowPreference(
                        title = stringResource(Res.string.network_dns_listen),
                        summary = uiState.dnsListen ?: stringResource(Res.string.common_not_modified),
                        onClick = { openStringDialog(dnsListenTitle, OverrideStorageHelper.KEY_DNS_LISTEN, uiState.dnsListen) },
                        enabled = dnsSubEnabled,
                    )
                    TriStatePreference(
                        title = "DNS IPv6",
                        value = uiState.dnsIpv6,
                        onValueChange = { viewModel.updateBoolean(OverrideStorageHelper.KEY_DNS_IPV6, it) },
                        enabled = dnsSubEnabled,
                    )
                    TriStatePreference(
                        title = "Prefer H3",
                        value = uiState.dnsPreferH3,
                        onValueChange = { viewModel.updateBoolean(OverrideStorageHelper.KEY_DNS_PREFER_H3, it) },
                        enabled = dnsSubEnabled,
                    )
                    TriStatePreference(
                        title = stringResource(Res.string.network_dns_use_hosts),
                        value = uiState.dnsUseHosts,
                        onValueChange = { viewModel.updateBoolean(OverrideStorageHelper.KEY_DNS_USE_HOSTS, it) },
                        enabled = dnsSubEnabled,
                    )
                    DnsEnhancedModePreference(
                        value = uiState.dnsEnhancedMode,
                        onValueChange = { viewModel.updateString(OverrideStorageHelper.KEY_DNS_ENHANCED_MODE, it) },
                        enabled = dnsSubEnabled,
                    )
                    ArrowPreference(
                        title = "Nameserver",
                        summary = listSummary(uiState.dnsNameservers),
                        onClick = { openListDialog("Nameserver", OverrideStorageHelper.KEY_DNS_NAMESERVERS, uiState.dnsNameservers) },
                        enabled = dnsSubEnabled,
                    )
                    ArrowPreference(
                        title = "Fallback",
                        summary = listSummary(uiState.dnsFallback),
                        onClick = { openListDialog("Fallback", OverrideStorageHelper.KEY_DNS_FALLBACK, uiState.dnsFallback) },
                        enabled = dnsSubEnabled,
                    )
                    val defaultNsTitle = stringResource(Res.string.network_dns_default_nameserver)
                    ArrowPreference(
                        title = defaultNsTitle,
                        summary = listSummary(uiState.dnsDefaultNameserver),
                        onClick = { openListDialog(defaultNsTitle, OverrideStorageHelper.KEY_DNS_DEFAULT_NAMESERVER, uiState.dnsDefaultNameserver) },
                        enabled = dnsSubEnabled,
                    )
                    val fakeipFilterTitle = stringResource(Res.string.network_dns_fakeip_filter)
                    ArrowPreference(
                        title = fakeipFilterTitle,
                        summary = listSummary(uiState.dnsFakeIpFilter),
                        onClick = { openListDialog(fakeipFilterTitle, OverrideStorageHelper.KEY_DNS_FAKEIP_FILTER, uiState.dnsFakeIpFilter) },
                        enabled = dnsSubEnabled,
                    )
                }
            }

            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }

    // === 端口编辑 Dialog ===
    PortEditDialog(
        show = showPortDialog,
        title = editingPortTitle,
        textState = portTextState,
        onDismiss = { showPortDialog = false },
        onConfirm = { port -> viewModel.updatePort(editingPortKey, port) },
        onReset = { viewModel.updatePort(editingPortKey, null) },
    )

    // === 字符串编辑 Dialog ===
    StringEditDialog(
        show = showStringDialog,
        title = editingStringTitle,
        textState = stringTextState,
        onDismiss = { showStringDialog = false },
        onConfirm = { value -> viewModel.updateString(editingStringKey, value) },
        onReset = { viewModel.updateString(editingStringKey, null) },
    )

    // === 列表编辑 Dialog ===
    ListEditDialog(
        show = showListDialog,
        title = editingListTitle,
        textState = listTextState,
        onDismiss = { showListDialog = false },
        onConfirm = { list -> viewModel.updateStringList(editingListKey, list) },
        onReset = { viewModel.updateStringList(editingListKey, null) },
    )
}

// === 私有组件 ===

@Composable
private fun LogLevelPreference(
    value: String?,
    onValueChange: (String?) -> Unit,
) {
    val notModifiedStr = stringResource(Res.string.common_not_modified)
    val items = listOf(notModifiedStr, "Info", "Warning", "Error", "Debug", "Silent")
    val values = listOf(null, "info", "warning", "error", "debug", "silent")
    val selectedIndex = values.indexOf(value).coerceAtLeast(0)

    OverlayDropdownPreference(
        title = stringResource(Res.string.network_log_level),
        summary = items[selectedIndex],
        items = items,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { index -> onValueChange(values[index]) },
    )
}

@Composable
private fun DnsEnhancedModePreference(
    value: String?,
    onValueChange: (String?) -> Unit,
    enabled: Boolean = true,
) {
    val notModifiedStr2 = stringResource(Res.string.common_not_modified)
    val items = listOf(notModifiedStr2, "Normal", "FakeIP", "Redir-Host")
    val values = listOf(null, "normal", "fake-ip", "redir-host")
    val selectedIndex = values.indexOf(value).coerceAtLeast(0)

    OverlayDropdownPreference(
        title = stringResource(Res.string.network_dns_enhanced_mode),
        summary = items[selectedIndex],
        items = items,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { index -> onValueChange(values[index]) },
        enabled = enabled,
    )
}

@Composable
private fun PortEditDialog(
    show: Boolean,
    title: String,
    textState: TextFieldState,
    onDismiss: () -> Unit,
    onConfirm: (Int?) -> Unit,
    onReset: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = title,
        summary = stringResource(Res.string.network_port_zero_hint),
        onDismissRequest = onDismiss,
    ) {
        TextField(
            state = textState,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(Res.string.network_port_label),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
            ),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(Res.string.common_not_modified),
                modifier = Modifier.weight(1f),
                onClick = {
                    onReset()
                    onDismiss()
                },
            )
            TextButton(
                text = stringResource(Res.string.common_cancel),
                modifier = Modifier.weight(1f),
                onClick = onDismiss,
            )
            TextButton(
                text = stringResource(Res.string.common_confirm),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    val port = textState.text.toString().toIntOrNull()
                    if (port != null && port in 0..65535) {
                        onConfirm(port)
                    }
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun StringEditDialog(
    show: Boolean,
    title: String,
    textState: TextFieldState,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
    onReset: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        TextField(
            state = textState,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(Res.string.network_input_value),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(Res.string.common_not_modified),
                modifier = Modifier.weight(1f),
                onClick = {
                    onReset()
                    onDismiss()
                },
            )
            TextButton(
                text = stringResource(Res.string.common_cancel),
                modifier = Modifier.weight(1f),
                onClick = onDismiss,
            )
            TextButton(
                text = stringResource(Res.string.common_confirm),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    val value = textState.text.toString().trim()
                    onConfirm(value.ifEmpty { null })
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun portSummary(port: Int?): String = if (port == null) stringResource(Res.string.common_not_modified) else "$port"

@Composable
private fun listSummary(list: List<String>?): String {
    if (list == null) return stringResource(Res.string.common_not_modified)
    if (list.isEmpty()) return stringResource(Res.string.common_cleared)
    return stringResource(Res.string.common_items_count, list.size)
}
