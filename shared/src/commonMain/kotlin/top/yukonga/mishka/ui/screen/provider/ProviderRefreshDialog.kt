package top.yukonga.mishka.ui.screen.provider

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.provider_updating_progress
import mishka.shared.generated.resources.provider_updating_single
import mishka.shared.generated.resources.provider_updating_title
import org.jetbrains.compose.resources.stringResource
import top.yukonga.mishka.viewmodel.RefreshProgress
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Provider 刷新进度对话框。不可手动关闭，调用方根据 refresh == null 控制 show。
 *
 * - `singleName != null`：显示 "正在更新 xxx…"（单条刷新）
 * - `singleName == null`：显示 "completed / total"（updateAll 并发刷新，每完成一条计数 +1）
 */
@Composable
fun ProviderRefreshDialog(
    show: Boolean,
    progress: RefreshProgress?,
) {
    WindowDialog(
        show = show,
        title = stringResource(Res.string.provider_updating_title),
        onDismissRequest = null,
        content = {
            val text = when {
                progress == null -> ""
                progress.singleName != null -> stringResource(
                    Res.string.provider_updating_single,
                    progress.singleName,
                )
                else -> stringResource(
                    Res.string.provider_updating_progress,
                    progress.completed,
                    progress.total,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Spacer(Modifier.width(16.dp))
                Text(
                    text = text,
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        },
    )
}
