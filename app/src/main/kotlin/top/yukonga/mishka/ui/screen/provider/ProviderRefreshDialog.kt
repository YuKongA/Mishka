package top.yukonga.mishka.ui.screen.provider

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.mishka.R
import top.yukonga.mishka.ui.component.blur.LocalPageBackdrop
import top.yukonga.mishka.viewmodel.RefreshProgress
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    GlassDialog(
        show = show,
        backdrop = LocalPageBackdrop.current,
        title = stringResource(R.string.provider_updating_title),
        onDismissRequest = {},
        content = {
            val text = when {
                progress == null -> ""
                progress.singleName != null -> stringResource(
                    R.string.provider_updating_single,
                    progress.singleName,
                )

                else -> stringResource(
                    R.string.provider_updating_progress,
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
