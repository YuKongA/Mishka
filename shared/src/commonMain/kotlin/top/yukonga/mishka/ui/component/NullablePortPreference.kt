package top.yukonga.mishka.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.common_cancel
import mishka.shared.generated.resources.common_confirm
import mishka.shared.generated.resources.common_not_modified
import mishka.shared.generated.resources.network_port_label
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 可空端口编辑偏好。
 * 显示 "不修改" 或端口号，点击弹出编辑对话框。
 */
@Composable
fun NullablePortPreference(
    title: String,
    value: Int?,
    showDialog: Boolean,
    textState: TextFieldState,
    onShowDialog: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (Int?) -> Unit,
) {
    ArrowPreference(
        title = title,
        summary = if (value == null) stringResource(Res.string.common_not_modified) else "$value",
        onClick = onShowDialog,
    )

    WindowDialog(
        show = showDialog,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        TextField(
            state = textState,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(Res.string.network_port_label),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
                    onConfirm(null)
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
