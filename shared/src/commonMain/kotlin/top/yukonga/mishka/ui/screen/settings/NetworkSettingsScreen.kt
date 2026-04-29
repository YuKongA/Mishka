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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.common_back
import mishka.shared.generated.resources.common_cancel
import mishka.shared.generated.resources.common_cleared
import mishka.shared.generated.resources.common_confirm
import mishka.shared.generated.resources.common_items_count
import mishka.shared.generated.resources.common_not_modified
import mishka.shared.generated.resources.dialog_reset_done
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
import top.yukonga.mishka.data.model.ConfigurationOverride
import top.yukonga.mishka.data.model.DnsOverride
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.ui.component.ListEditDialog
import top.yukonga.mishka.ui.component.RestartRequiredHint
import top.yukonga.mishka.ui.component.TriStatePreference
import top.yukonga.mishka.viewmodel.NetworkSettingsViewModel
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
    viewModel: NetworkSettingsViewModel,
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()
    val resetDoneMsg = stringResource(Res.string.dialog_reset_done)

    fun updateTop(transform: (ConfigurationOverride) -> ConfigurationOverride) {
        viewModel.update(transform)
    }

    fun updateDns(transform: (DnsOverride) -> DnsOverride) {
        viewModel.updateDns(transform)
    }

    // 端口编辑 Dialog 状态（setter 在 open 时绑定）
    var showPortDialog by remember { mutableStateOf(false) }
    var editingPortTitle by remember { mutableStateOf("") }
    var editingPortSetter by remember { mutableStateOf<(Int?) -> Unit>({}) }
    val portTextState = rememberTextFieldState()

    // 字符串编辑 Dialog 状态
    var showStringDialog by remember { mutableStateOf(false) }
    var editingStringTitle by remember { mutableStateOf("") }
    var editingStringSetter by remember { mutableStateOf<(String?) -> Unit>({}) }
    val stringTextState = rememberTextFieldState()

    // 列表编辑 Dialog 状态
    var showListDialog by remember { mutableStateOf(false) }
    var editingListTitle by remember { mutableStateOf("") }
    var editingListSetter by remember { mutableStateOf<(List<String>?) -> Unit>({}) }
    val listTextState = rememberTextFieldState()

    fun openPortDialog(title: String, value: Int?, setter: (Int?) -> Unit) {
        editingPortTitle = title
        editingPortSetter = setter
        portTextState.edit { replace(0, length, value?.toString() ?: "") }
        showPortDialog = true
    }

    fun openStringDialog(title: String, value: String?, setter: (String?) -> Unit) {
        editingStringTitle = title
        editingStringSetter = setter
        stringTextState.edit { replace(0, length, value ?: "") }
        showStringDialog = true
    }

    fun openListDialog(title: String, value: List<String>?, setter: (List<String>?) -> Unit) {
        editingListTitle = title
        editingListSetter = setter
        listTextState.edit { replace(0, length, value?.joinToString("\n") ?: "") }
        showListDialog = true
    }

    val dns = uiState.dns

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
                        onClick = { openPortDialog(httpPortTitle, uiState.httpPort) { v -> updateTop { it.copy(httpPort = v) } } },
                    )
                    val socksPortTitle = stringResource(Res.string.network_socks_port)
                    ArrowPreference(
                        title = socksPortTitle,
                        summary = portSummary(uiState.socksPort),
                        onClick = { openPortDialog(socksPortTitle, uiState.socksPort) { v -> updateTop { it.copy(socksPort = v) } } },
                    )
                    val redirPortTitle = stringResource(Res.string.network_redir_port)
                    ArrowPreference(
                        title = redirPortTitle,
                        summary = portSummary(uiState.redirPort),
                        onClick = { openPortDialog(redirPortTitle, uiState.redirPort) { v -> updateTop { it.copy(redirPort = v) } } },
                    )
                    val tproxyPortTitle = stringResource(Res.string.network_tproxy_port)
                    ArrowPreference(
                        title = tproxyPortTitle,
                        summary = portSummary(uiState.tproxyPort),
                        onClick = { openPortDialog(tproxyPortTitle, uiState.tproxyPort) { v -> updateTop { it.copy(tproxyPort = v) } } },
                    )
                    val mixedPortTitle = stringResource(Res.string.network_mixed_port)
                    ArrowPreference(
                        title = mixedPortTitle,
                        summary = portSummary(uiState.mixedPort),
                        onClick = { openPortDialog(mixedPortTitle, uiState.mixedPort) { v -> updateTop { it.copy(mixedPort = v) } } },
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
                        onValueChange = { v -> updateTop { it.copy(allowLan = v) } },
                    )
                    TriStatePreference(
                        title = "IPv6",
                        value = uiState.ipv6,
                        onValueChange = { v -> updateTop { it.copy(ipv6 = v) } },
                    )
                    val bindAddrTitle = stringResource(Res.string.network_bind_address)
                    ArrowPreference(
                        title = bindAddrTitle,
                        summary = uiState.bindAddress ?: stringResource(Res.string.common_not_modified),
                        onClick = { openStringDialog(bindAddrTitle, uiState.bindAddress) { v -> updateTop { it.copy(bindAddress = v) } } },
                    )
                    LogLevelPreference(
                        value = uiState.logLevel,
                        onValueChange = { v -> updateTop { it.copy(logLevel = v) } },
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
                        value = dns?.enable,
                        onValueChange = { v -> updateDns { it.copy(enable = v) } },
                    )
                    // DNS 显式关闭时禁用子项；null（不修改）仍保留可编辑，允许用户预配置子覆写
                    val dnsSubEnabled = dns?.enable != false
                    val dnsListenTitle = stringResource(Res.string.network_dns_listen_title)
                    ArrowPreference(
                        title = stringResource(Res.string.network_dns_listen),
                        summary = dns?.listen ?: stringResource(Res.string.common_not_modified),
                        onClick = { openStringDialog(dnsListenTitle, dns?.listen) { v -> updateDns { it.copy(listen = v) } } },
                        enabled = dnsSubEnabled,
                    )
                    TriStatePreference(
                        title = "DNS IPv6",
                        value = dns?.ipv6,
                        onValueChange = { v -> updateDns { it.copy(ipv6 = v) } },
                        enabled = dnsSubEnabled,
                    )
                    TriStatePreference(
                        title = "Prefer H3",
                        value = dns?.preferH3,
                        onValueChange = { v -> updateDns { it.copy(preferH3 = v) } },
                        enabled = dnsSubEnabled,
                    )
                    TriStatePreference(
                        title = stringResource(Res.string.network_dns_use_hosts),
                        value = dns?.useHosts,
                        onValueChange = { v -> updateDns { it.copy(useHosts = v) } },
                        enabled = dnsSubEnabled,
                    )
                    DnsEnhancedModePreference(
                        value = dns?.enhancedMode,
                        onValueChange = { v -> updateDns { it.copy(enhancedMode = v) } },
                        enabled = dnsSubEnabled,
                    )
                    ArrowPreference(
                        title = "Nameserver",
                        summary = listSummary(dns?.nameserver),
                        onClick = { openListDialog("Nameserver", dns?.nameserver) { v -> updateDns { it.copy(nameserver = v) } } },
                        enabled = dnsSubEnabled,
                    )
                    ArrowPreference(
                        title = "Fallback",
                        summary = listSummary(dns?.fallback),
                        onClick = { openListDialog("Fallback", dns?.fallback) { v -> updateDns { it.copy(fallback = v) } } },
                        enabled = dnsSubEnabled,
                    )
                    val defaultNsTitle = stringResource(Res.string.network_dns_default_nameserver)
                    ArrowPreference(
                        title = defaultNsTitle,
                        summary = listSummary(dns?.defaultNameserver),
                        onClick = { openListDialog(defaultNsTitle, dns?.defaultNameserver) { v -> updateDns { it.copy(defaultNameserver = v) } } },
                        enabled = dnsSubEnabled,
                    )
                    val fakeipFilterTitle = stringResource(Res.string.network_dns_fakeip_filter)
                    ArrowPreference(
                        title = fakeipFilterTitle,
                        summary = listSummary(dns?.fakeIpFilter),
                        onClick = { openListDialog(fakeipFilterTitle, dns?.fakeIpFilter) { v -> updateDns { it.copy(fakeIpFilter = v) } } },
                        enabled = dnsSubEnabled,
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
        }
    }

    // === 端口编辑 Dialog ===
    PortEditDialog(
        show = showPortDialog,
        title = editingPortTitle,
        textState = portTextState,
        onDismiss = { showPortDialog = false },
        onConfirm = { port -> editingPortSetter(port) },
        onReset = {
            editingPortSetter(null)
            showToast(resetDoneMsg)
        },
    )

    // === 字符串编辑 Dialog ===
    StringEditDialog(
        show = showStringDialog,
        title = editingStringTitle,
        textState = stringTextState,
        onDismiss = { showStringDialog = false },
        onConfirm = { value -> editingStringSetter(value) },
        onReset = {
            editingStringSetter(null)
            showToast(resetDoneMsg)
        },
    )

    // === 列表编辑 Dialog ===
    ListEditDialog(
        show = showListDialog,
        title = editingListTitle,
        textState = listTextState,
        onDismiss = { showListDialog = false },
        onConfirm = { list -> editingListSetter(list) },
        onReset = {
            editingListSetter(null)
            showToast(resetDoneMsg)
        },
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
