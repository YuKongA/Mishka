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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.common_cancel
import mishka.shared.generated.resources.subscription_import_config
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 导入进度对话框：校验配置、下载外部资源时显示。
 * 不可通过点击外部关闭（`onDismissRequest = null`），避免误触中断 commit 原子段；
 * 传入 [onCancel] 时在底部显示"取消"按钮，由 ViewModel 侧 cancel 协程。
 */
@Composable
fun ImportProgressDialog(
    show: Boolean,
    step: String,
    title: String = stringResource(Res.string.subscription_import_config),
    onCancel: (() -> Unit)? = null,
) {
    WindowDialog(
        show = show,
        title = title,
        onDismissRequest = null,
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
                    TextButton(
                        text = stringResource(Res.string.common_cancel),
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    )
}
