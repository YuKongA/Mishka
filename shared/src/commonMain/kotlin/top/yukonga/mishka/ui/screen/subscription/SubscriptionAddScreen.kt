package top.yukonga.mishka.ui.screen.subscription

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.mishka.viewmodel.SubscriptionViewModel

/**
 * 创建配置 —— 选择添加方式（文件 / URL）
 */
@Composable
fun SubscriptionAddScreen(
    viewModel: SubscriptionViewModel? = null,
    onBack: () -> Unit = {},
    onPickFile: () -> Unit = {},
    onNavigateUrl: () -> Unit = {},
    bottomPadding: Dp = 0.dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val uiState by viewModel?.uiState?.collectAsState()
        ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = "创建配置",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = bottomPadding,
            ),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 12.dp),
                ) {
                    ArrowPreference(
                        title = "文件",
                        summary = if (uiState.importProgress != null) uiState.importProgress!!.step else "从文件导入",
                        enabled = !uiState.isLoading,
                        onClick = onPickFile,
                    )
                    ArrowPreference(
                        title = "URL",
                        summary = "从 URL 导入",
                        enabled = !uiState.isLoading,
                        onClick = onNavigateUrl,
                    )
                }
            }
            if (uiState.error.isNotEmpty()) {
                item(key = "error") {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 6.dp),
                        insideMargin = PaddingValues(16.dp),
                    ) {
                        Text(
                            text = uiState.error,
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }

    ImportProgressDialog(
        show = uiState.isLoading,
        step = uiState.importProgress?.step ?: "处理中...",
    )
}
