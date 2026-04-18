package top.yukonga.mishka.ui.screen.settings

import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.common_back
import mishka.shared.generated.resources.common_cleared
import mishka.shared.generated.resources.common_items_count
import mishka.shared.generated.resources.common_not_modified
import mishka.shared.generated.resources.dialog_reset_done
import mishka.shared.generated.resources.meta_basic
import mishka.shared.generated.resources.meta_find_process_mode
import mishka.shared.generated.resources.meta_geodata_mode
import mishka.shared.generated.resources.meta_settings_title
import mishka.shared.generated.resources.meta_sniffer
import mishka.shared.generated.resources.meta_sniffer_enable
import mishka.shared.generated.resources.meta_sniffer_force_dns_mapping
import mishka.shared.generated.resources.meta_sniffer_force_domain
import mishka.shared.generated.resources.meta_sniffer_override_dest
import mishka.shared.generated.resources.meta_sniffer_parse_pure_ip
import mishka.shared.generated.resources.meta_sniffer_skip_domain
import mishka.shared.generated.resources.meta_tcp_concurrent
import mishka.shared.generated.resources.meta_unified_delay
import org.jetbrains.compose.resources.stringResource
import top.yukonga.mishka.data.model.ConfigurationOverride
import top.yukonga.mishka.data.model.SnifferOverride
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.ui.component.ListEditDialog
import top.yukonga.mishka.ui.component.RestartRequiredHint
import top.yukonga.mishka.ui.component.TriStatePreference
import top.yukonga.mishka.viewmodel.OverrideSettingsViewModel
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

@Composable
fun MetaSettingsScreen(
    viewModel: OverrideSettingsViewModel,
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.state.collectAsState()
    val scrollBehavior = MiuixScrollBehavior()
    val resetDoneMsg = stringResource(Res.string.dialog_reset_done)

    fun updateTop(transform: (ConfigurationOverride) -> ConfigurationOverride) {
        viewModel.update(transform)
    }

    fun updateSniffer(transform: (SnifferOverride) -> SnifferOverride) {
        viewModel.update { state ->
            val current = state.sniffer ?: SnifferOverride()
            val next = transform(current)
            val allNull = next == SnifferOverride()
            state.copy(sniffer = if (allNull) null else next)
        }
    }

    var showListDialog by remember { mutableStateOf(false) }
    var editingListTitle by remember { mutableStateOf("") }
    var editingListSetter by remember { mutableStateOf<(List<String>?) -> Unit>({}) }
    val listTextState = rememberTextFieldState()

    fun openListDialog(title: String, value: List<String>?, setter: (List<String>?) -> Unit) {
        editingListTitle = title
        editingListSetter = setter
        listTextState.edit { replace(0, length, value?.joinToString("\n") ?: "") }
        showListDialog = true
    }

    val sniffer = uiState.sniffer

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(Res.string.meta_settings_title),
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

            // === 基本 ===
            item { SmallTitle(text = stringResource(Res.string.meta_basic)) }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 6.dp),
                ) {
                    TriStatePreference(
                        title = stringResource(Res.string.meta_unified_delay),
                        value = uiState.unifiedDelay,
                        onValueChange = { v -> updateTop { it.copy(unifiedDelay = v) } },
                    )
                    TriStatePreference(
                        title = stringResource(Res.string.meta_geodata_mode),
                        value = uiState.geodataMode,
                        onValueChange = { v -> updateTop { it.copy(geodataMode = v) } },
                    )
                    TriStatePreference(
                        title = stringResource(Res.string.meta_tcp_concurrent),
                        value = uiState.tcpConcurrent,
                        onValueChange = { v -> updateTop { it.copy(tcpConcurrent = v) } },
                    )
                    FindProcessModePreference(
                        value = uiState.findProcessMode,
                        onValueChange = { v -> updateTop { it.copy(findProcessMode = v) } },
                    )
                }
            }

            // === 嗅探器 ===
            item { SmallTitle(text = stringResource(Res.string.meta_sniffer)) }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 6.dp),
                ) {
                    TriStatePreference(
                        title = stringResource(Res.string.meta_sniffer_enable),
                        value = sniffer?.enable,
                        onValueChange = { v -> updateSniffer { it.copy(enable = v) } },
                    )
                    TriStatePreference(
                        title = stringResource(Res.string.meta_sniffer_force_dns_mapping),
                        value = sniffer?.forceDnsMapping,
                        onValueChange = { v -> updateSniffer { it.copy(forceDnsMapping = v) } },
                    )
                    TriStatePreference(
                        title = stringResource(Res.string.meta_sniffer_parse_pure_ip),
                        value = sniffer?.parsePureIp,
                        onValueChange = { v -> updateSniffer { it.copy(parsePureIp = v) } },
                    )
                    TriStatePreference(
                        title = stringResource(Res.string.meta_sniffer_override_dest),
                        value = sniffer?.overrideDestination,
                        onValueChange = { v -> updateSniffer { it.copy(overrideDestination = v) } },
                    )
                    val forceDomainTitle = stringResource(Res.string.meta_sniffer_force_domain)
                    ArrowPreference(
                        title = forceDomainTitle,
                        summary = listSummary(sniffer?.forceDomain),
                        onClick = { openListDialog(forceDomainTitle, sniffer?.forceDomain) { v -> updateSniffer { it.copy(forceDomain = v) } } },
                    )
                    val skipDomainTitle = stringResource(Res.string.meta_sniffer_skip_domain)
                    ArrowPreference(
                        title = skipDomainTitle,
                        summary = listSummary(sniffer?.skipDomain),
                        onClick = { openListDialog(skipDomainTitle, sniffer?.skipDomain) { v -> updateSniffer { it.copy(skipDomain = v) } } },
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
        }
    }

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

@Composable
private fun FindProcessModePreference(
    value: String?,
    onValueChange: (String?) -> Unit,
) {
    val notModifiedStr = stringResource(Res.string.common_not_modified)
    val items = listOf(notModifiedStr, "Off", "Strict", "Always")
    val values = listOf(null, "off", "strict", "always")
    val selectedIndex = values.indexOf(value).coerceAtLeast(0)

    OverlayDropdownPreference(
        title = stringResource(Res.string.meta_find_process_mode),
        summary = items[selectedIndex],
        items = items,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { index -> onValueChange(values[index]) },
    )
}

@Composable
private fun listSummary(list: List<String>?): String {
    if (list == null) return stringResource(Res.string.common_not_modified)
    if (list.isEmpty()) return stringResource(Res.string.common_cleared)
    return stringResource(Res.string.common_items_count, list.size)
}
