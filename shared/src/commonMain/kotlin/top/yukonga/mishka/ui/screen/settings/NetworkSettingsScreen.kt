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
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
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
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.mishka.data.model.ConfigPatch
import top.yukonga.mishka.viewmodel.SettingsViewModel

@Composable
fun NetworkSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit = {},
    bottomPadding: Dp = 0.dp,
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollBehavior = MiuixScrollBehavior()
    val config = uiState.config

    // 端口编辑 Dialog 状态
    var showPortDialog by remember { mutableStateOf(false) }
    var editingPortType by remember { mutableStateOf("") }
    var editingPortTitle by remember { mutableStateOf("") }
    val portTextState = rememberTextFieldState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = "网络设置",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
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
                        summary = portSummary(config?.port),
                        onClick = {
                            editingPortType = "port"
                            editingPortTitle = "HTTP 端口"
                            portTextState.edit { replace(0, length, portEditValue(config?.port)) }
                            showPortDialog = true
                        },
                    )
                    ArrowPreference(
                        title = "SOCKS 端口",
                        summary = portSummary(config?.socksPort),
                        onClick = {
                            editingPortType = "socks"
                            editingPortTitle = "SOCKS 端口"
                            portTextState.edit { replace(0, length, portEditValue(config?.socksPort)) }
                            showPortDialog = true
                        },
                    )
                    ArrowPreference(
                        title = "Mixed 端口",
                        summary = portSummary(config?.mixedPort),
                        onClick = {
                            editingPortType = "mixed"
                            editingPortTitle = "Mixed 端口"
                            portTextState.edit { replace(0, length, portEditValue(config?.mixedPort)) }
                            showPortDialog = true
                        },
                    )
                }
            }

            item { SmallTitle(text = "网络选项") }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 6.dp),
                ) {
                    SwitchPreference(
                        title = "允许局域网",
                        summary = "允许局域网设备访问代理",
                        checked = config?.allowLan ?: false,
                        onCheckedChange = { checked ->
                            viewModel.patchConfig(ConfigPatch(allowLan = checked))
                        },
                    )
                    SwitchPreference(
                        title = "IPv6",
                        summary = "启用 IPv6 支持",
                        checked = config?.ipv6 ?: false,
                        onCheckedChange = { checked ->
                            viewModel.patchConfig(ConfigPatch(ipv6 = checked))
                        },
                    )
                }
            }

            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }

    // 端口编辑 Dialog
    WindowDialog(
        show = showPortDialog,
        title = editingPortTitle,
        onDismissRequest = { showPortDialog = false },
    ) {
        TextField(
            state = portTextState,
            modifier = Modifier.fillMaxWidth(),
            label = "端口号",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = "取消",
                modifier = Modifier.weight(1f),
                onClick = { showPortDialog = false },
            )
            TextButton(
                text = "确定",
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    val port = portTextState.text.toString().toIntOrNull()
                    if (port != null && port in 0..65535) {
                        val patch = when (editingPortType) {
                            "port" -> ConfigPatch(port = port)
                            "socks" -> ConfigPatch(socksPort = port)
                            "mixed" -> ConfigPatch(mixedPort = port)
                            else -> null
                        }
                        if (patch != null) viewModel.patchConfig(patch)
                    }
                    showPortDialog = false
                },
            )
        }
    }
}

private fun portSummary(port: Int?): String = if (port == null || port == 0) "未设置" else "$port"

private fun portEditValue(port: Int?): String = if (port == null || port == 0) "" else "$port"
