package top.yukonga.mishka.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.common_cancel
import mishka.shared.generated.resources.common_confirm
import mishka.shared.generated.resources.root_tether_ifaces_hint
import mishka.shared.generated.resources.root_tether_scan
import mishka.shared.generated.resources.root_tether_scan_empty
import mishka.shared.generated.resources.root_tether_scan_result_title
import mishka.shared.generated.resources.root_tether_scan_scanning
import org.jetbrains.compose.resources.stringResource
import top.yukonga.mishka.platform.scanTetherInterfacesAsRoot
import top.yukonga.mishka.platform.showToast
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 热点接口名编辑对话框。多行 TextField（每行一个接口名）+ [检测当前接口] 辅助按钮。
 * 扫描按钮通过 `su ip -o addr show` 解析当前持有热点私网 IP 的接口，弹二级勾选 Dialog 后覆盖输入框。
 */
@Composable
fun TetherInterfaceEditDialog(
    show: Boolean,
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val textState = rememberTextFieldState()
    val scanningMsg = stringResource(Res.string.root_tether_scan_scanning)
    val emptyMsg = stringResource(Res.string.root_tether_scan_empty)
    val scope = rememberCoroutineScope()

    var showScanDialog by remember { mutableStateOf(false) }
    var scanResult by remember { mutableStateOf<List<String>>(emptyList()) }
    val selectedMap = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }

    // 每次 Dialog 打开时把 storage 的逗号分隔值回填成多行文本
    LaunchedEffect(show, initialValue) {
        if (show) {
            textState.setTextAndPlaceCursorAtEnd(
                initialValue.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            )
        }
    }

    WindowDialog(
        show = show,
        title = title,
        summary = stringResource(Res.string.root_tether_ifaces_hint),
        onDismissRequest = onDismiss,
    ) {
        TextField(
            state = textState,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        TextButton(
            text = stringResource(Res.string.root_tether_scan),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    showToast(scanningMsg)
                    val found = withContext(Dispatchers.IO) { scanTetherInterfacesAsRoot() }
                    if (found.isEmpty()) {
                        showToast(emptyMsg)
                    } else {
                        scanResult = found
                        selectedMap.clear()
                        // 默认只勾选"像热点"的接口：wlan0 是主 STA 客户端，不勾；
                        // ap*/wlan[1-9]/swlan*/wlan0_AP/rndis*/usb*/bt-pan 视为候选热点
                        found.forEach { selectedMap[it] = isLikelyTetherInterface(it) }
                        showScanDialog = true
                    }
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
                    val csv = textState.text.toString()
                        .lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .joinToString(",")
                    onConfirm(csv)
                    onDismiss()
                },
            )
        }
    }

    ScanResultDialog(
        show = showScanDialog,
        candidates = scanResult,
        selected = selectedMap,
        onDismiss = { showScanDialog = false },
        onApply = { picked ->
            if (picked.isNotEmpty()) {
                textState.setTextAndPlaceCursorAtEnd(picked.joinToString("\n"))
            }
            showScanDialog = false
        },
    )
}

@Composable
private fun ScanResultDialog(
    show: Boolean,
    candidates: List<String>,
    selected: SnapshotStateMap<String, Boolean>,
    onDismiss: () -> Unit,
    onApply: (List<String>) -> Unit,
) {
    WindowDialog(
        show = show,
        title = stringResource(Res.string.root_tether_scan_result_title),
        onDismissRequest = onDismiss,
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(candidates) { iface ->
                val checked = selected[iface] == true
                BasicComponent(
                    title = iface,
                    endActions = {
                        Checkbox(
                            state = if (checked) ToggleableState.On else ToggleableState.Off,
                            onClick = { selected[iface] = !checked },
                        )
                    },
                    onClick = { selected[iface] = !checked },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
                    val picked = candidates.filter { selected[it] == true }
                    onApply(picked)
                },
            )
        }
    }
}

@Composable
fun tetherInterfaceSummary(value: String): String {
    val items = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    return if (items.isEmpty()) "—" else items.joinToString(", ")
}

/**
 * 判断接口名是否"像热点接口"，用于扫描结果默认勾选。
 *
 * 明确排除：`wlan0`（主 STA Wi-Fi 客户端，几乎所有 Android 设备的主 Wi-Fi 都用这个名字）。
 * 明确纳入：`ap*` / `wlan[1-9]` / `swlan*` / `wlan0_AP` / `rndis*` / `usb*` / `bt-pan`。
 * 其他未识别名保守视为"非热点"，不默认勾选（用户可手动勾）。
 */
private fun isLikelyTetherInterface(name: String): Boolean {
    if (name == "wlan0") return false
    if (name.startsWith("ap")) return true
    if (name == "wlan0_AP") return true
    if (name.startsWith("wlan") && name.length > 4 && name[4].isDigit() && name[4] != '0') return true
    if (name.startsWith("swlan")) return true
    if (name.startsWith("rndis")) return true
    if (name.startsWith("usb")) return true
    if (name == "bt-pan") return true
    return false
}
