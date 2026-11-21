package top.yukonga.mishka.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import top.yukonga.mishka.R
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.WifiPolicyAction
import top.yukonga.mishka.platform.WifiPolicyController
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.BackNavigationIcon
import top.yukonga.mishka.ui.component.CardItem
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.LocalPageBackdrop
import top.yukonga.mishka.ui.component.blur.PageBackdropBox
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.groupedCardItems
import top.yukonga.mishka.ui.component.overScrollVertical
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Add
import top.yukonga.miuix.kmp.icon.glass.Delete
import top.yukonga.miuix.kmp.icon.glass.WiFi
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun WifiPolicyScreen(
    storage: PlatformStorage,
    controller: WifiPolicyController?,
    onRequestPermission: (((Boolean) -> Unit) -> Unit)? = null,
    onBack: () -> Unit = {},
) {
    val scrollBehavior = MiuixScrollBehavior()
    var enabled by remember {
        mutableStateOf(storage.getString(StorageKeys.WIFI_POLICY_ENABLED, "false") == "true")
    }
    var action by remember {
        mutableStateOf(WifiPolicyAction.fromStorage(storage.getString(StorageKeys.WIFI_POLICY_ACTION, "")))
    }
    var notifySwitch by remember {
        mutableStateOf(storage.getString(StorageKeys.WIFI_POLICY_NOTIFY_SWITCH, "true") != "false")
    }
    var hideMonitorNotification by remember {
        mutableStateOf(storage.getString(StorageKeys.WIFI_POLICY_HIDE_MONITOR_NOTIFICATION, "false") == "true")
    }
    var ssids by remember {
        mutableStateOf(storage.getStringSet(StorageKeys.WIFI_POLICY_SSIDS, emptySet()).toList().sorted())
    }
    var permissionGranted by remember {
        mutableStateOf(controller?.hasRequiredPermission() == true)
    }
    var currentSsid by remember {
        mutableStateOf(controller?.currentSsid())
    }
    val ssidState = rememberTextFieldState()

    val permissionRequired = stringResource(R.string.wifi_policy_permission_required)
    val ssidEmpty = stringResource(R.string.wifi_policy_ssid_empty)
    val ssidDuplicate = stringResource(R.string.wifi_policy_ssid_duplicate)
    val currentWifiEmpty = stringResource(R.string.wifi_policy_ssid_scan_empty)

    fun refreshStatus() {
        permissionGranted = controller?.hasRequiredPermission() == true
        currentSsid = controller?.currentSsid()
    }

    fun persistEnabled(value: Boolean) {
        storage.putString(StorageKeys.WIFI_POLICY_ENABLED, value.toString())
        enabled = value
        if (value) controller?.startMonitor() else controller?.stopMonitor()
    }

    fun requestPermissionThenEnable() {
        if (controller == null || onRequestPermission == null) {
            showToast(permissionRequired, long = true)
            return
        }
        if (controller.hasRequiredPermission()) {
            refreshStatus()
            persistEnabled(true)
            return
        }
        onRequestPermission { granted ->
            permissionGranted = granted
            currentSsid = controller.currentSsid()
            if (granted) {
                persistEnabled(true)
            } else {
                persistEnabled(false)
                showToast(permissionRequired, long = true)
            }
        }
    }

    fun requestPermissionOnly(onGranted: () -> Unit) {
        if (controller == null || onRequestPermission == null) {
            showToast(permissionRequired, long = true)
            return
        }
        if (controller.hasRequiredPermission()) {
            refreshStatus()
            onGranted()
            return
        }
        onRequestPermission { granted ->
            permissionGranted = granted
            currentSsid = controller.currentSsid()
            if (granted) {
                onGranted()
            } else {
                showToast(permissionRequired, long = true)
            }
        }
    }

    fun persistSsids(value: List<String>) {
        ssids = value.distinct().sorted()
        storage.putStringSet(StorageKeys.WIFI_POLICY_SSIDS, ssids.toSet())
        if (enabled) controller?.evaluateNow()
    }

    fun normalizeInputSsid(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }.trim()
    }

    fun addSsid() {
        val value = normalizeInputSsid(ssidState.text.toString())
        when {
            value.isEmpty() -> showToast(ssidEmpty)
            value in ssids -> showToast(ssidDuplicate)
            else -> {
                persistSsids(ssids + value)
                ssidState.clearText()
            }
        }
    }

    fun fillCurrentSsid() {
        requestPermissionOnly {
            val candidate = controller?.currentSsid()
            if (candidate == null) {
                showToast(currentWifiEmpty)
            } else {
                ssidState.setTextAndPlaceCursorAtEnd(candidate)
                refreshStatus()
            }
        }
    }

    val actionItems = listOf(
        stringResource(R.string.wifi_policy_action_stop),
        stringResource(R.string.wifi_policy_action_direct),
    )
    val actionSummary = when (action) {
        WifiPolicyAction.StopService -> stringResource(R.string.wifi_policy_action_stop_summary)
        WifiPolicyAction.DirectMode -> stringResource(R.string.wifi_policy_action_direct_summary)
    }
    val ssidCardItems = listOf(
        CardItem("ssidInput") {
            SsidInputRow(
                state = ssidState,
                onScan = { fillCurrentSsid() },
                onAdd = { addSsid() },
            )
        },
    ) + ssids.map { ssid ->
        CardItem("ssid:$ssid") {
            SsidListItem(
                ssid = ssid,
                onDelete = { persistSsids(ssids - ssid) },
            )
        }
    }
    val statusCardItems = listOf(
        CardItem("current") {
            ArrowPreference(
                title = stringResource(R.string.wifi_policy_current),
                summary = currentSsid ?: stringResource(R.string.wifi_policy_unknown_ssid),
                onClick = { refreshStatus() },
            )
        },
        CardItem("permission") {
            ArrowPreference(
                title = stringResource(R.string.wifi_policy_permission),
                summary = stringResource(
                    if (permissionGranted) R.string.wifi_policy_permission_granted
                    else R.string.wifi_policy_permission_missing
                ),
                onClick = {
                    if (!permissionGranted) requestPermissionThenEnable() else refreshStatus()
                },
            )
        },
    )

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null

    PageBackdropBox {
        Scaffold(
            topBar = {
                BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                    AdaptiveTopAppBar(
                        title = stringResource(R.string.wifi_policy_title),
                        scrollBehavior = scrollBehavior,
                        navigationIcon = { BackNavigationIcon(onBack) },
                    )
                }
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                    .horizontalCutoutPadding()
                    .scrollEndHaptic()
                    .overScrollVertical(scrollBehavior)
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .imePadding(),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                ),
            ) {
                item { SmallTitle(text = stringResource(R.string.wifi_policy_basic)) }
                groupedCardItems(
                    keyPrefix = "wifi_policy_control",
                    items = listOf(
                        CardItem("enable") {
                            SwitchPreference(
                                title = stringResource(R.string.wifi_policy_enable),
                                summary = stringResource(R.string.wifi_policy_enable_summary),
                                checked = enabled,
                                onCheckedChange = { checked ->
                                    if (checked) requestPermissionThenEnable() else persistEnabled(false)
                                },
                            )
                        },
                        CardItem("notifySwitch") {
                            SwitchPreference(
                                title = stringResource(R.string.wifi_policy_notify_switch),
                                summary = stringResource(R.string.wifi_policy_notify_switch_summary),
                                checked = notifySwitch,
                                onCheckedChange = { checked ->
                                    notifySwitch = checked
                                    storage.putString(StorageKeys.WIFI_POLICY_NOTIFY_SWITCH, checked.toString())
                                },
                            )
                        },
                        CardItem("hideMonitorNotification") {
                            SwitchPreference(
                                title = stringResource(R.string.wifi_policy_hide_monitor_notification),
                                summary = stringResource(R.string.wifi_policy_hide_monitor_notification_summary),
                                checked = hideMonitorNotification,
                                onCheckedChange = { checked ->
                                    hideMonitorNotification = checked
                                    storage.putString(
                                        StorageKeys.WIFI_POLICY_HIDE_MONITOR_NOTIFICATION,
                                        checked.toString(),
                                    )
                                    if (enabled) controller?.startMonitor()
                                },
                            )
                        },
                        CardItem("action") {
                            GlassDropdownPreference(
                                title = stringResource(R.string.wifi_policy_action),
                                backdrop = LocalPageBackdrop.current,
                                summary = actionSummary,
                                items = actionItems,
                                selectedIndex = if (action == WifiPolicyAction.DirectMode) 1 else 0,
                                onSelectedIndexChange = { index ->
                                    action = if (index == 1) WifiPolicyAction.DirectMode else WifiPolicyAction.StopService
                                    storage.putString(StorageKeys.WIFI_POLICY_ACTION, action.storageValue)
                                    if (enabled) controller?.evaluateNow()
                                },
                                enabled = enabled,
                            )
                        },
                    ),
                )

                item { SmallTitle(text = stringResource(R.string.wifi_policy_ssids_title)) }
                groupedCardItems(
                    keyPrefix = "wifi_policy_ssids",
                    items = ssidCardItems,
                    outerBottomPadding = 12.dp,
                )
                groupedCardItems(
                    keyPrefix = "wifi_policy_status",
                    items = statusCardItems,
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
    }
}

@Composable
private fun SsidInputRow(
    state: TextFieldState,
    onScan: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            onClick = onScan,
            minHeight = 40.dp,
            minWidth = 40.dp,
            backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
        ) {
            Icon(
                modifier = Modifier.size(28.dp),
                imageVector = MiuixIcons.Glass.WiFi,
                contentDescription = stringResource(R.string.wifi_policy_ssid_scan),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        TextField(
            state = state,
            // 与两侧 40dp 圆形按钮等高，默认 16dp 垂直内边距会把高度撑到 ~53dp
            modifier = Modifier
                .weight(1f)
                .height(40.dp),
            insideMargin = DpSize(16.dp, 8.dp),
            label = stringResource(R.string.wifi_policy_ssid_add_placeholder),
            useLabelAsPlaceholder = true,
        )
        IconButton(
            onClick = onAdd,
            minHeight = 40.dp,
            minWidth = 40.dp,
            backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
        ) {
            Icon(
                modifier = Modifier.size(28.dp),
                imageVector = MiuixIcons.Glass.Add,
                contentDescription = stringResource(R.string.wifi_policy_ssid_add),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun SsidListItem(
    ssid: String,
    onDelete: () -> Unit,
) {
    BasicComponent(
        title = ssid,
        endActions = {
            IconButton(
                onClick = onDelete,
                minHeight = 35.dp,
                minWidth = 35.dp,
                backgroundColor = Color.Transparent,
            ) {
                Icon(
                    modifier = Modifier.size(28.dp),
                    imageVector = MiuixIcons.Glass.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        },
    )
}
