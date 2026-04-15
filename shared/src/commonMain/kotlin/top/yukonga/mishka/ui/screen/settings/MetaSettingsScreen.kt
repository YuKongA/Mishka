package top.yukonga.mishka.ui.screen.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.mishka.data.repository.OverrideStorageHelper
import top.yukonga.mishka.ui.component.ListEditDialog
import top.yukonga.mishka.ui.component.TriStatePreference
import top.yukonga.mishka.viewmodel.OverrideSettingsViewModel

@Composable
fun MetaSettingsScreen(
    viewModel: OverrideSettingsViewModel,
    onBack: () -> Unit = {},
    bottomPadding: Dp = 0.dp,
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollBehavior = MiuixScrollBehavior()

    // 列表编辑 Dialog 状态
    var showListDialog by remember { mutableStateOf(false) }
    var editingListKey by remember { mutableStateOf("") }
    var editingListTitle by remember { mutableStateOf("") }
    val listTextState = rememberTextFieldState()

    fun openListDialog(title: String, key: String, value: List<String>?) {
        editingListKey = key
        editingListTitle = title
        listTextState.edit { replace(0, length, value?.joinToString("\n") ?: "") }
        showListDialog = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "Meta 设置",
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
            // === 基本 ===
            item { SmallTitle(text = "基本") }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 6.dp),
                ) {
                    TriStatePreference(
                        title = "统一延迟",
                        value = uiState.unifiedDelay,
                        onValueChange = { viewModel.updateBoolean(OverrideStorageHelper.KEY_UNIFIED_DELAY, it) },
                    )
                    TriStatePreference(
                        title = "Geodata 模式",
                        value = uiState.geodataMode,
                        onValueChange = { viewModel.updateBoolean(OverrideStorageHelper.KEY_GEODATA_MODE, it) },
                    )
                    TriStatePreference(
                        title = "TCP 并发",
                        value = uiState.tcpConcurrent,
                        onValueChange = { viewModel.updateBoolean(OverrideStorageHelper.KEY_TCP_CONCURRENT, it) },
                    )
                    FindProcessModePreference(
                        value = uiState.findProcessMode,
                        onValueChange = { viewModel.updateString(OverrideStorageHelper.KEY_FIND_PROCESS_MODE, it) },
                    )
                }
            }

            // === 嗅探器 ===
            item { SmallTitle(text = "嗅探器") }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 6.dp),
                ) {
                    TriStatePreference(
                        title = "启用嗅探器",
                        value = uiState.snifferEnable,
                        onValueChange = { viewModel.updateBoolean(OverrideStorageHelper.KEY_SNIFFER_ENABLE, it) },
                    )
                    TriStatePreference(
                        title = "强制 DNS 映射",
                        value = uiState.snifferForceDnsMapping,
                        onValueChange = { viewModel.updateBoolean(OverrideStorageHelper.KEY_SNIFFER_FORCE_DNS_MAPPING, it) },
                    )
                    TriStatePreference(
                        title = "解析纯 IP",
                        value = uiState.snifferParsePureIp,
                        onValueChange = { viewModel.updateBoolean(OverrideStorageHelper.KEY_SNIFFER_PARSE_PURE_IP, it) },
                    )
                    TriStatePreference(
                        title = "覆写目标地址",
                        value = uiState.snifferOverrideDestination,
                        onValueChange = { viewModel.updateBoolean(OverrideStorageHelper.KEY_SNIFFER_OVERRIDE_DEST, it) },
                    )
                    ArrowPreference(
                        title = "强制嗅探域名",
                        summary = listSummary(uiState.snifferForceDomain),
                        onClick = { openListDialog("强制嗅探域名", OverrideStorageHelper.KEY_SNIFFER_FORCE_DOMAIN, uiState.snifferForceDomain) },
                    )
                    ArrowPreference(
                        title = "跳过嗅探域名",
                        summary = listSummary(uiState.snifferSkipDomain),
                        onClick = { openListDialog("跳过嗅探域名", OverrideStorageHelper.KEY_SNIFFER_SKIP_DOMAIN, uiState.snifferSkipDomain) },
                    )
                }
            }

            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }

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

@Composable
private fun FindProcessModePreference(
    value: String?,
    onValueChange: (String?) -> Unit,
) {
    val items = listOf("不修改", "Off", "Strict", "Always")
    val values = listOf(null, "off", "strict", "always")
    val selectedIndex = values.indexOf(value).coerceAtLeast(0)

    OverlayDropdownPreference(
        title = "进程匹配模式",
        summary = items[selectedIndex],
        items = items,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { index -> onValueChange(values[index]) },
    )
}

private fun listSummary(list: List<String>?): String {
    if (list == null) return "不修改"
    if (list.isEmpty()) return "已清除"
    return "${list.size} 条"
}
