package top.yukonga.mishka.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.common_back
import mishka.shared.generated.resources.common_cancel
import mishka.shared.generated.resources.common_confirm
import mishka.shared.generated.resources.common_not_modified
import mishka.shared.generated.resources.dialog_reset_done
import mishka.shared.generated.resources.external_control_controller_hint
import mishka.shared.generated.resources.external_control_title
import mishka.shared.generated.resources.network_external_controller
import mishka.shared.generated.resources.network_input_value
import org.jetbrains.compose.resources.stringResource
import top.yukonga.mishka.data.repository.OverrideStorageHelper
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.ui.component.RestartRequiredHint
import top.yukonga.mishka.viewmodel.OverrideSettingsViewModel
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
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun ExternalControlScreen(
    viewModel: OverrideSettingsViewModel,
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollBehavior = MiuixScrollBehavior()
    val resetDoneMsg = stringResource(Res.string.dialog_reset_done)
    val notModifiedStr = stringResource(Res.string.common_not_modified)

    var showEditDialog by remember { mutableStateOf(false) }
    val controllerTextState = rememberTextFieldState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(Res.string.external_control_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        val layoutDirection = LocalLayoutDirection.current
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(Res.string.common_back),
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.graphicsLayer {
                                scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                            },
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
            ),
        ) {
            item { RestartRequiredHint() }

            item {
                Spacer(Modifier.height(6.dp))
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    ArrowPreference(
                        title = stringResource(Res.string.network_external_controller),
                        summary = uiState.externalController ?: notModifiedStr,
                        onClick = {
                            controllerTextState.edit {
                                replace(0, length, uiState.externalController ?: "")
                            }
                            showEditDialog = true
                        },
                    )
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.external_control_controller_hint),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
        }
    }

    WindowDialog(
        show = showEditDialog,
        title = stringResource(Res.string.network_external_controller),
        onDismissRequest = { showEditDialog = false },
    ) {
        TextField(
            state = controllerTextState,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(Res.string.network_input_value),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(Res.string.common_not_modified),
                modifier = Modifier.weight(1f),
                onClick = {
                    viewModel.updateString(OverrideStorageHelper.KEY_EXTERNAL_CONTROLLER, null)
                    showToast(resetDoneMsg)
                    showEditDialog = false
                },
            )
            TextButton(
                text = stringResource(Res.string.common_cancel),
                modifier = Modifier.weight(1f),
                onClick = { showEditDialog = false },
            )
            TextButton(
                text = stringResource(Res.string.common_confirm),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    val value = controllerTextState.text.toString().trim().takeIf { it.isNotEmpty() }
                    viewModel.updateString(OverrideStorageHelper.KEY_EXTERNAL_CONTROLLER, value)
                    showEditDialog = false
                },
            )
        }
    }
}

