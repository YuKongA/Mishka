package top.yukonga.mishka.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * 列表编辑对话框，用于编辑 DNS 服务器、域名列表等。
 * 每行一条，支持三态：不修改（null）/ 编辑内容 / 清除。
 */
@Composable
fun ListEditDialog(
    show: Boolean,
    title: String,
    textState: TextFieldState,
    onDismiss: () -> Unit,
    onConfirm: (List<String>?) -> Unit,
    onReset: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        TextField(
            state = textState,
            modifier = Modifier.fillMaxWidth().height(160.dp),
            label = stringResource(Res.string.network_one_per_line),
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
                text = stringResource(Res.string.common_clear),
                modifier = Modifier.weight(1f),
                onClick = {
                    onConfirm(emptyList())
                    onDismiss()
                },
            )
            TextButton(
                text = stringResource(Res.string.common_confirm),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    val text = textState.text.toString()
                    val list = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    onConfirm(list)
                    onDismiss()
                },
            )
        }
    }
}
