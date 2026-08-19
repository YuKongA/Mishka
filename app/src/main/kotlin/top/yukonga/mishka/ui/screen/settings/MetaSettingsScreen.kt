package top.yukonga.mishka.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import top.yukonga.mishka.R
import top.yukonga.mishka.data.bridge.AgeKeyPair
import top.yukonga.mishka.data.bridge.MishkaCoreBridge
import top.yukonga.mishka.domain.model.ConfigurationOverride
import top.yukonga.mishka.domain.model.SnifferOverride
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.CardItem
import top.yukonga.mishka.ui.component.ListEditDialog
import top.yukonga.mishka.ui.component.RestartRequiredHint
import top.yukonga.mishka.ui.component.TriStatePreference
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.groupedCardItems
import top.yukonga.mishka.ui.platform.setPlainText
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.viewmodel.MetaSettingsViewModel
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun MetaSettingsScreen(
    viewModel: MetaSettingsViewModel,
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()
    val resetDoneMsg = stringResource(R.string.dialog_reset_done)

    fun updateTop(transform: (ConfigurationOverride) -> ConfigurationOverride) {
        viewModel.update(transform)
    }

    fun updateSniffer(transform: (SnifferOverride) -> SnifferOverride) {
        viewModel.updateSniffer(transform)
    }

    var showAgeDialog by remember { mutableStateOf(false) }
    var ageKeyPair by remember { mutableStateOf<AgeKeyPair?>(null) }
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

    val failedMsg = stringResource(R.string.meta_age_keygen_failed)
    val genKey: (Boolean) -> Unit = { hybrid ->
        val pair = MishkaCoreBridge.generateAgeKeyPair(hybrid)
        if (pair != null) {
            ageKeyPair = pair
            showAgeDialog = true
        } else {
            showToast(failedMsg)
        }
    }

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.meta_settings_title),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            val layoutDirection = LocalLayoutDirection.current
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.common_back),
                                tint = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                                },
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
            ),
        ) {
            item { RestartRequiredHint() }

            item { SmallTitle(text = stringResource(R.string.meta_basic)) }
            groupedCardItems(
                keyPrefix = "meta_basic",
                items = listOf(
                    CardItem("unifiedDelay") {
                        TriStatePreference(
                            title = stringResource(R.string.meta_unified_delay),
                            value = uiState.unifiedDelay,
                            onValueChange = { v -> updateTop { it.copy(unifiedDelay = v) } },
                        )
                    },
                    CardItem("geodataMode") {
                        TriStatePreference(
                            title = stringResource(R.string.meta_geodata_mode),
                            value = uiState.geodataMode,
                            onValueChange = { v -> updateTop { it.copy(geodataMode = v) } },
                        )
                    },
                    CardItem("tcpConcurrent") {
                        TriStatePreference(
                            title = stringResource(R.string.meta_tcp_concurrent),
                            value = uiState.tcpConcurrent,
                            onValueChange = { v -> updateTop { it.copy(tcpConcurrent = v) } },
                        )
                    },
                    CardItem("findProcessMode") {
                        FindProcessModePreference(
                            value = uiState.findProcessMode,
                            onValueChange = { v -> updateTop { it.copy(findProcessMode = v) } },
                        )
                    },
                ),
            )

            item { SmallTitle(text = stringResource(R.string.meta_sniffer)) }
            groupedCardItems(
                keyPrefix = "meta_sniffer",
                items = listOf(
                    CardItem("enable") {
                        TriStatePreference(
                            title = stringResource(R.string.meta_sniffer_enable),
                            value = sniffer?.enable,
                            onValueChange = { v -> updateSniffer { it.copy(enable = v) } },
                        )
                    },
                    CardItem("forceDnsMapping") {
                        TriStatePreference(
                            title = stringResource(R.string.meta_sniffer_force_dns_mapping),
                            value = sniffer?.forceDnsMapping,
                            onValueChange = { v -> updateSniffer { it.copy(forceDnsMapping = v) } },
                        )
                    },
                    CardItem("parsePureIp") {
                        TriStatePreference(
                            title = stringResource(R.string.meta_sniffer_parse_pure_ip),
                            value = sniffer?.parsePureIp,
                            onValueChange = { v -> updateSniffer { it.copy(parsePureIp = v) } },
                        )
                    },
                    CardItem("overrideDest") {
                        TriStatePreference(
                            title = stringResource(R.string.meta_sniffer_override_dest),
                            value = sniffer?.overrideDestination,
                            onValueChange = { v -> updateSniffer { it.copy(overrideDestination = v) } },
                        )
                    },
                    CardItem("forceDomain") {
                        val forceDomainTitle = stringResource(R.string.meta_sniffer_force_domain)
                        ArrowPreference(
                            title = forceDomainTitle,
                            summary = listSummary(sniffer?.forceDomain),
                            onClick = {
                                openListDialog(
                                    forceDomainTitle,
                                    sniffer?.forceDomain
                                ) { v -> updateSniffer { it.copy(forceDomain = v) } }
                            },
                        )
                    },
                    CardItem("skipDomain") {
                        val skipDomainTitle = stringResource(R.string.meta_sniffer_skip_domain)
                        ArrowPreference(
                            title = skipDomainTitle,
                            summary = listSummary(sniffer?.skipDomain),
                            onClick = {
                                openListDialog(
                                    skipDomainTitle,
                                    sniffer?.skipDomain
                                ) { v -> updateSniffer { it.copy(skipDomain = v) } }
                            },
                        )
                    },
                ),
            )

            item { SmallTitle(text = stringResource(R.string.meta_age)) }
            groupedCardItems(
                keyPrefix = "meta_age",
                items = listOf(
                    CardItem("generate") {
                        ArrowPreference(
                            title = stringResource(R.string.meta_age_generate),
                            summary = stringResource(R.string.meta_age_generate_summary),
                            onClick = { genKey(false) },
                        )
                    },
                    CardItem("generateHybrid") {
                        ArrowPreference(
                            title = stringResource(R.string.meta_age_generate_hybrid),
                            summary = stringResource(R.string.meta_age_generate_hybrid_summary),
                            onClick = { genKey(true) },
                        )
                    },
                ),
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

    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val copiedMsg = stringResource(R.string.common_copied)
    val agePair = ageKeyPair
    WindowDialog(
        show = showAgeDialog && agePair != null,
        title = stringResource(R.string.meta_age_keypair_title),
        onDismissRequest = { showAgeDialog = false },
    ) {
        if (agePair != null) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 密钥内容可滚动，按钮固定在底部
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SmallTitle(
                        text = stringResource(R.string.meta_age_secret_key),
                        insideMargin = PaddingValues(vertical = 8.dp),
                    )
                    Text(
                        text = agePair.secretKey,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    )
                    SmallTitle(
                        text = stringResource(R.string.meta_age_public_key),
                        insideMargin = PaddingValues(vertical = 8.dp),
                    )
                    Text(
                        text = agePair.publicKey,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        text = stringResource(R.string.meta_age_copy_secret),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            clipboardScope.launch { clipboard.setPlainText(agePair.secretKey) }
                            showToast(copiedMsg)
                        },
                    )
                    TextButton(
                        text = stringResource(R.string.meta_age_copy_public),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            clipboardScope.launch { clipboard.setPlainText(agePair.publicKey) }
                            showToast(copiedMsg)
                        },
                    )
                }
                TextButton(
                    text = stringResource(R.string.common_close),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    onClick = { showAgeDialog = false },
                )
            }
        }
    }
}

@Composable
private fun FindProcessModePreference(
    value: String?,
    onValueChange: (String?) -> Unit,
) {
    val notModifiedStr = stringResource(R.string.common_not_modified)
    val items = listOf(notModifiedStr, "Off", "Strict", "Always")
    val values = listOf(null, "off", "strict", "always")
    val selectedIndex = values.indexOf(value).coerceAtLeast(0)

    OverlayDropdownPreference(
        title = stringResource(R.string.meta_find_process_mode),
        summary = items[selectedIndex],
        items = items,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { index -> onValueChange(values[index]) },
    )
}

@Composable
private fun listSummary(list: List<String>?): String {
    if (list == null) return stringResource(R.string.common_not_modified)
    if (list.isEmpty()) return stringResource(R.string.common_cleared)
    return pluralStringResource(R.plurals.common_items_count, list.size, list.size)
}
