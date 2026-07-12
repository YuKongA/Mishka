package top.yukonga.mishka.ui.screen.settings

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import top.yukonga.mishka.data.bridge.MishkaCoreBridge
import top.yukonga.mishka.platform.ProfileFileManager
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.viewmodel.SubscriptionViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.scripta.editor.CodeEditor
import top.yukonga.scripta.editor.EditorColors
import top.yukonga.scripta.editor.EditorLanguage
import top.yukonga.scripta.editor.rememberCodeEditorController

@Composable
fun FileManagerEditorScreen(
    uuid: String,
    relativePath: String,
    subscriptionViewModel: SubscriptionViewModel? = null,
    onBack: () -> Unit = {},
) {
    val fileManager = subscriptionViewModel?.fileManager
    val controller = rememberCodeEditorController()
    val scope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(uuid, relativePath, fileManager) {
        val content = withContext(Dispatchers.IO) {
            fileManager?.readImportedFile(uuid, relativePath)
        } ?: ""
        controller.setDocument(content)
    }

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                // 编辑屏纵向空间优先，固定小顶栏（编辑器内部滚动不经宿主 nestedScroll，大标题也无法折叠）
                SmallTopAppBar(
                    title = relativePath,
                    color = barColor,
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
                    actions = {
                        val canSave = controller.isModified && !isSaving && fileManager != null
                        IconButton(
                            enabled = canSave,
                            onClick = onSave@{
                                if (fileManager == null) return@onSave
                                // 版本号与文本必须同刻捕获：校验期间的继续编辑不能被 markSaved 误吞
                                val version = controller.documentVersion
                                val newContent = controller.getText(controller.lineEnding)
                                // 取被编辑订阅的自定义 UA：校验阶段也走 in-process bridge，需要复用同一 UA
                                // 否则 GeoIP/provider 缺失时下载会用默认 UA 触发服务端拦截
                                val userAgent = subscriptionViewModel.uiState.value
                                    .subscriptions
                                    .find { it.id == uuid }
                                    ?.userAgent
                                    .orEmpty()
                                isSaving = true
                                scope.launch {
                                    val err = runCatching {
                                        saveWithValidation(
                                            fileManager = fileManager,
                                            uuid = uuid,
                                            relativePath = relativePath,
                                            newContent = newContent,
                                            userAgent = userAgent,
                                        )
                                    }
                                    isSaving = false
                                    err.onSuccess { errMsg ->
                                        if (errMsg == null) {
                                            controller.markSaved(version)
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
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    imageVector = MiuixIcons.Ok,
                                    contentDescription = stringResource(Res.string.file_manager_save),
                                    tint = if (canSave) MiuixTheme.colorScheme.onSurface
                                    else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
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

            // 编辑器自行消费底部系统栏 / IME insets，外层不加 imePadding / navigationBarsPadding。
            // 断开 MiuixTheme 全局注入的弹簧越界工厂：编辑器滚动保持硬钳制。
            CompositionLocalProvider(LocalOverscrollFactory provides null) {
                CodeEditor(
                    controller = controller,
                    language = if (isYamlPath(relativePath)) EditorLanguage.Yaml else EditorLanguage.PlainText,
                    colors = if (isSystemInDarkTheme()) EditorColors.Default else EditorColors.Light,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

private fun isYamlPath(path: String): Boolean = path.endsWith(".yaml") || path.endsWith(".yml")

// 仅校验 YAML（config.yaml / .yml / .yaml），其他文件直接写盘。返回 null 表示通过。
private suspend fun saveWithValidation(
    fileManager: ProfileFileManager,
    uuid: String,
    relativePath: String,
    newContent: String,
    userAgent: String,
): String? = withContext(Dispatchers.IO) {
    if (!isYamlPath(relativePath)) {
        fileManager.writeImportedFile(uuid, relativePath, newContent)
        return@withContext null
    }
    val original = fileManager.readImportedFile(uuid, relativePath)
    fileManager.writeImportedFile(uuid, relativePath, newContent)
    val workDir = fileManager.getImportedDir(uuid)
    val err = runCatching {
        MishkaCoreBridge.fetchAndValid(
            workDir = workDir,
            url = "",
            force = false,
            httpProxy = null,
            userAgent = userAgent,
            // imported/ 下的 config 已在导入时解密为明文，重校验无需 age 密钥
            ageSecretKey = "",
            onProgress = {},
        )
    }.exceptionOrNull()?.message
    if (err != null && original != null) {
        fileManager.writeImportedFile(uuid, relativePath, original)
    }
    err
}
