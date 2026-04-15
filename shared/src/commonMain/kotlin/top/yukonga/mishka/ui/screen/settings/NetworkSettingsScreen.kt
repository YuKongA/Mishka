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
import androidx.compose.runtime.collectAsState
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
import top.yukonga.mishka.data.repository.OverrideStorageHelper
import top.yukonga.mishka.ui.component.ListEditDialog
import top.yukonga.mishka.ui.component.TriStatePreference
import top.yukonga.mishka.viewmodel.OverrideSettingsViewModel

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
                title = "覆写设置",
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
            // === 代理端口 ===
            item { SmallTitle(text = "代理端口") }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 6.dp),
                ) {
                    ArrowPreference(
                        title = "HTTP 端口",
                        summary = portSummary(uiState.httpPort),
                        onClick = { openPortDialog("HTTP 端口", OverrideStorageHelper.KEY_HTTP_PORT, uiState.httpPort) },
                    )
                    ArrowPreference(
                        title = "SOCKS 端口",
                        summary = portSummary(uiState.socksPort),
                        onClick = { openPortDialog("SOCKS 端口", OverrideStorageHelper.KEY_SOCKS_PORT, uiState.socksPort) },
                    )
                    ArrowPreference(
                        title = "Redir 端口",
                        summary = portSummary(uiState.redirPort),
                        onClick = { openPortDialog("Redir 端口", OverrideStorageHelper.KEY_REDIR_PORT, uiState.redirPort) },
                    )
                    ArrowPreference(
                        title = "TProxy 端口",
                        summary = portSummary(uiState.tproxyPort),
                        onClick = { openPortDialog("TProxy 端口", OverrideStorageHelper.KEY_TPROXY_PORT, uiState.tproxyPort) },
                    )
                    ArrowPreference(
                        title = "Mixed 端口",
                        summary = portSummary(uiState.mixedPort),
                        onClick = { openPortDialog("Mixed 端口", OverrideStorageHelper.KEY_MIXED_PORT, uiState.mixedPort) },
                    )
                }
            }

            // === 网络选项 ===
            item { SmallTitle(text = "网络选项") }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 6.dp),
                ) {
                    TriStatePreference(
                        title = "允许局域网",
                        value = uiState.allowLan,
                        onValueChange = { viewModel.updateBoolean(OverrideStorageHelper.KEY_ALLOW_LAN, it) },
                    )
                    TriStatePreference(
                        title = "IPv6",
                        value = uiState.ipv6,
                        onValueChange = { viewModel.updateBoolean(OverrideStorageHelper.KEY_IPV6, it) },
                    )
                    ArrowPreference(
                        title = "绑定地址",
                        summary = uiState.bindAddress ?: "不修改",
                        onClick = { openStringDialog("绑定地址", OverrideStorageHelper.KEY_BIND_ADDRESS, uiState.bindAddress) },
                    )
                    LogLevelPreference(
                        value = uiState.logLevel,
                        onValueChange = { viewModel.updateString(OverrideStorageHelper.KEY_LOG_LEVEL, it) },
                    )
                }
            }

            // === DNS ===
            item { SmallTitle(text = "DNS") }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 6.dp),
                ) {
                    TriStatePreference(
                        title = "启用 DNS",
                        value = uiState.dnsEnable,
                        onValueChange = { viewModel.updateBoolean(OverrideStorageHelper.KEY_DNS_ENABLE, it) },
                    )
                    ArrowPreference(
                        title = "监听地址",
                        summary = uiState.dnsListen ?: "不修改",
                        onClick = { openStringDialog("DNS 监听地址", OverrideStorageHelper.KEY_DNS_LISTEN, uiState.dnsListen) },
                    )
                    TriStatePreference(
                        title = "DNS IPv6",
                        value = uiState.dnsIpv6,
                        onValueChange = { viewModel.updateBoolean(OverrideStorageHelper.KEY_DNS_IPV6, it) },
                    )
                    TriStatePreference(
                        title = "Prefer H3",
                        value = uiState.dnsPreferH3,
                        onValueChange = { viewModel.updateBoolean(OverrideStorageHelper.KEY_DNS_PREFER_H3, it) },
                    )
                    TriStatePreference(
                        title = "使用 Hosts",
                        value = uiState.dnsUseHosts,
                        onValueChange = { viewModel.updateBoolean(OverrideStorageHelper.KEY_DNS_USE_HOSTS, it) },
                    )
                    DnsEnhancedModePreference(
                        value = uiState.dnsEnhancedMode,
                        onValueChange = { viewModel.updateString(OverrideStorageHelper.KEY_DNS_ENHANCED_MODE, it) },
                    )
                    ArrowPreference(
                        title = "Nameserver",
                        summary = listSummary(uiState.dnsNameservers),
                        onClick = { openListDialog("Nameserver", OverrideStorageHelper.KEY_DNS_NAMESERVERS, uiState.dnsNameservers) },
                    )
                    ArrowPreference(
                        title = "Fallback",
                        summary = listSummary(uiState.dnsFallback),
                        onClick = { openListDialog("Fallback", OverrideStorageHelper.KEY_DNS_FALLBACK, uiState.dnsFallback) },
                    )
                    ArrowPreference(
                        title = "默认 Nameserver",
                        summary = listSummary(uiState.dnsDefaultNameserver),
                        onClick = { openListDialog("默认 Nameserver", OverrideStorageHelper.KEY_DNS_DEFAULT_NAMESERVER, uiState.dnsDefaultNameserver) },
                    )
                    ArrowPreference(
                        title = "FakeIP 过滤",
                        summary = listSummary(uiState.dnsFakeIpFilter),
                        onClick = { openListDialog("FakeIP 过滤", OverrideStorageHelper.KEY_DNS_FAKEIP_FILTER, uiState.dnsFakeIpFilter) },
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
    val items = listOf("不修改", "Info", "Warning", "Error", "Debug", "Silent")
    val values = listOf(null, "info", "warning", "error", "debug", "silent")
    val selectedIndex = values.indexOf(value).coerceAtLeast(0)

    OverlayDropdownPreference(
        title = "日志等级",
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
) {
    val items = listOf("不修改", "Normal", "FakeIP", "Redir-Host")
    val values = listOf(null, "normal", "fake-ip", "redir-host")
    val selectedIndex = values.indexOf(value).coerceAtLeast(0)

    OverlayDropdownPreference(
        title = "增强模式",
        summary = items[selectedIndex],
        items = items,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { index -> onValueChange(values[index]) },
    )
}

@Composable
private fun PortEditDialog(
    show: Boolean,
    title: String,
    textState: androidx.compose.foundation.text.input.TextFieldState,
    onDismiss: () -> Unit,
    onConfirm: (Int?) -> Unit,
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
            label = "端口号 (0-65535)",
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            ),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = "不修改",
                modifier = Modifier.weight(1f),
                onClick = {
                    onReset()
                    onDismiss()
                },
            )
            TextButton(
                text = "取消",
                modifier = Modifier.weight(1f),
                onClick = onDismiss,
            )
            TextButton(
                text = "确定",
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
    textState: androidx.compose.foundation.text.input.TextFieldState,
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
            label = "输入值",
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = "不修改",
                modifier = Modifier.weight(1f),
                onClick = {
                    onReset()
                    onDismiss()
                },
            )
            TextButton(
                text = "取消",
                modifier = Modifier.weight(1f),
                onClick = onDismiss,
            )
            TextButton(
                text = "确定",
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

private fun portSummary(port: Int?): String = if (port == null) "不修改" else "$port"

private fun listSummary(list: List<String>?): String {
    if (list == null) return "不修改"
    if (list.isEmpty()) return "已清除"
    return "${list.size} 条"
}
