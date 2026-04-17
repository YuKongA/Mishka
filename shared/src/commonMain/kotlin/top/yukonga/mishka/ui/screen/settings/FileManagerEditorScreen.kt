package top.yukonga.mishka.ui.screen.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.common_back
import mishka.shared.generated.resources.file_manager_edit_warning
import mishka.shared.generated.resources.file_manager_save
import mishka.shared.generated.resources.file_manager_save_failed
import mishka.shared.generated.resources.file_manager_saved
import mishka.shared.generated.resources.file_manager_validating
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import top.yukonga.mishka.platform.ProfileFileManager
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.viewmodel.SubscriptionViewModel
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun FileManagerEditorScreen(
    uuid: String,
    relativePath: String,
    subscriptionViewModel: SubscriptionViewModel? = null,
    onBack: () -> Unit = {},
) {
    val scrollBehavior = MiuixScrollBehavior()
    val fileManager = subscriptionViewModel?.fileManager
    val textState = rememberTextFieldState()
    val scope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(uuid, relativePath, fileManager) {
        val content = withContext(Dispatchers.IO) {
            fileManager?.readImportedFile(uuid, relativePath)
        } ?: ""
        textState.edit { replace(0, length, content) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = relativePath,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        val ld = LocalLayoutDirection.current
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(Res.string.common_back),
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.graphicsLayer {
                                scaleX = if (ld == LayoutDirection.Rtl) -1f else 1f
                            },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().scrollEndHaptic().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
            ),
        ) {
            item {
                Spacer(Modifier.height(12.dp))
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.file_manager_edit_warning),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            item {
                TextField(
                    state = textState,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 12),
                )
            }

            item {
                TextButton(
                    text = if (isSaving) stringResource(Res.string.file_manager_validating)
                    else stringResource(Res.string.file_manager_save),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    enabled = !isSaving && fileManager != null,
                    onClick = {
                        if (fileManager == null) return@TextButton
                        val newContent = textState.text.toString()
                        isSaving = true
                        scope.launch {
                            val err = runCatching {
                                saveWithValidation(
                                    fileManager = fileManager,
                                    uuid = uuid,
                                    relativePath = relativePath,
                                    newContent = newContent,
                                )
                            }
                            isSaving = false
                            err.onSuccess { errMsg ->
                                if (errMsg == null) {
                                    showToast(getString(Res.string.file_manager_saved))
                                } else {
                                    showToast(getString(Res.string.file_manager_save_failed, errMsg), long = true)
                                }
                            }.onFailure { t ->
                                showToast(
                                    getString(Res.string.file_manager_save_failed, t.message ?: "unknown"),
                                    long = true,
                                )
                            }
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
        }
    }
}

/**
 * 对 YAML 文件（config.yaml / .yml）做 mihomo -t 校验后落盘；其他文件直接写入。
 * 返回 null 表示成功，非空字符串为错误信息。
 */
private suspend fun saveWithValidation(
    fileManager: ProfileFileManager,
    uuid: String,
    relativePath: String,
    newContent: String,
): String? = withContext(Dispatchers.IO) {
    val needsValidate = relativePath == "config.yaml" || relativePath.endsWith(".yml") || relativePath.endsWith(".yaml")
    if (!needsValidate) {
        fileManager.writeImportedFile(uuid, relativePath, newContent)
        return@withContext null
    }
    val original = fileManager.readImportedFile(uuid, relativePath)
    fileManager.writeImportedFile(uuid, relativePath, newContent)
    val workDir = fileManager.getDir(uuid)
    val err = fileManager.validate(workDir, relativePath)
    if (err != null && original != null) {
        fileManager.writeImportedFile(uuid, relativePath, original)
    }
    err
}
