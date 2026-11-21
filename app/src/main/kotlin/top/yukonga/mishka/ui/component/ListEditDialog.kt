package top.yukonga.mishka.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.mishka.R
import top.yukonga.mishka.ui.component.blur.LocalPageBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassTextField

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
    GlassDialog(
        show = show,
        backdrop = LocalPageBackdrop.current,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        GlassTextField(
            state = textState,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.network_one_per_line),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassDialogTextButton(
                text = stringResource(R.string.common_clear),
                modifier = Modifier.weight(1f),
                onClick = {
                    onConfirm(emptyList())
                    onDismiss()
                },
            )
            GlassDialogTextButton(
                text = stringResource(R.string.common_confirm),
                modifier = Modifier.weight(1f),
                role = GlassDialogButtonRole.Primary,
                onClick = {
                    val text = textState.text.toString()
                    val list = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    onConfirm(list)
                    onDismiss()
                },
            )
        }
        GlassDialogTextButton(
            text = stringResource(R.string.common_not_modified),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            onClick = {
                onReset()
                onDismiss()
            },
        )
    }
}
