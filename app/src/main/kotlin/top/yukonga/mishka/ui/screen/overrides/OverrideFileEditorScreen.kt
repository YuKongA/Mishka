package top.yukonga.mishka.ui.screen.overrides

import android.annotation.SuppressLint
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.mishka.R
import top.yukonga.mishka.domain.model.OverrideFormat
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.TopBarIconButton
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.theme.LocalAppDarkMode
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.viewmodel.OverrideProfileViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.scripta.editor.CodeEditor
import top.yukonga.scripta.editor.EditorColors
import top.yukonga.scripta.editor.EditorLanguage
import top.yukonga.scripta.editor.rememberCodeEditorController
import top.yukonga.miuix.kmp.icon.glass.Ok as GlassOk

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun OverrideFileEditorScreen(
    overrideId: String,
    viewModel: OverrideProfileViewModel,
    onBack: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val profile = state.profiles.find { it.id == overrideId }
    if (profile == null) {
        LaunchedEffect(overrideId) { onBack() }
        return
    }

    val controller = rememberCodeEditorController()
    val context = LocalContext.current
    var saving by remember { mutableStateOf(false) }
    ClearOverrideErrorOnExit(viewModel)

    LaunchedEffect(overrideId) {
        viewModel.clearError()
        controller.setDocument(viewModel.readContent(overrideId))
    }
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading) saving = false
    }

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = profile.name,
                    expandable = false,
                    navigationIcon = { OverrideBackButton(onBack) },
                    actions = {
                        val canSave = controller.isModified && !state.isLoading && !saving
                        TopBarIconButton(
                            enabled = canSave,
                            isLoading = saving || state.isLoading,
                            glassIcon = MiuixIcons.Glass.GlassOk,
                            contentDescription = stringResource(R.string.common_save),
                            onClick = {
                                val version = controller.documentVersion
                                val content = controller.getText(controller.lineEnding)
                                saving = true
                                viewModel.saveContent(overrideId, content) {
                                    controller.markSaved(version)
                                    saving = false
                                    showToast(context.getString(R.string.file_manager_saved))
                                }
                            },
                        )
                    },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .horizontalCutoutPadding()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            if (state.error.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Text(
                        text = state.error,
                        color = StatusColors.danger,
                        fontSize = 13.sp,
                    )
                }
            }

            CompositionLocalProvider(LocalOverscrollFactory provides null) {
                CodeEditor(
                    controller = controller,
                    language = if (profile.format == OverrideFormat.Yaml) EditorLanguage.Yaml else EditorLanguage.JavaScript,
                    colors = if (LocalAppDarkMode.current) EditorColors.Default else EditorColors.Light,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}
