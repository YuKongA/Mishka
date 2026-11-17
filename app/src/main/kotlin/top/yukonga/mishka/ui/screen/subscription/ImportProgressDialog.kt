package top.yukonga.mishka.ui.screen.subscription

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.mishka.R
import top.yukonga.mishka.ui.component.GlassDialogTextButton
import top.yukonga.mishka.ui.component.blur.LocalPageBackdrop
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 导入进度对话框：校验配置、下载外部资源时显示。
 * 不可通过点击外部关闭（`onDismissRequest = null`），避免误触中断 commit 原子段；
 * 传入 [onCancel] 时在底部显示"取消"按钮，由 ViewModel 侧 cancel 协程。
 */
@Composable
fun ImportProgressDialog(
    show: Boolean,
    step: String,
    title: String = stringResource(R.string.subscription_import_config),
    onCancel: (() -> Unit)? = null,
) {
    GlassDialog(
        show = show,
        backdrop = LocalPageBackdrop.current,
        title = title,
        onDismissRequest = {},
        content = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = step,
                        fontSize = 15.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
                if (onCancel != null) {
                    Spacer(Modifier.height(16.dp))
                    GlassDialogTextButton(
                        text = stringResource(R.string.common_cancel),
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    )
}
