package top.yukonga.mishka.ui.screen.subscription

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 导入进度对话框：校验配置、下载外部资源时显示。
 * 不可手动关闭，完成或出错后由调用方控制 show = false。
 */
@Composable
fun ImportProgressDialog(
    show: Boolean,
    step: String,
) {
    WindowDialog(
        show = show,
        title = "导入配置",
        onDismissRequest = null,
        content = {
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
        },
    )
}
