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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.mishka.R
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.BackNavigationIcon
import top.yukonga.mishka.ui.component.CardItem
import top.yukonga.mishka.ui.component.GlassDialogTextButton
import top.yukonga.mishka.ui.component.RestartRequiredHint
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.LocalPageBackdrop
import top.yukonga.mishka.ui.component.blur.PageBackdropBox
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.groupedCardItems
import top.yukonga.mishka.ui.component.overScrollVertical
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.viewmodel.ExternalControlViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun ExternalControlScreen(
    viewModel: ExternalControlViewModel,
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()
    val resetDoneMsg = stringResource(R.string.dialog_reset_done)
    val notModifiedStr = stringResource(R.string.common_not_modified)

    var showEditDialog by remember { mutableStateOf(false) }
    val controllerTextState = rememberTextFieldState()

    var showSecretDialog by remember { mutableStateOf(false) }
    val secretTextState = rememberTextFieldState()

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null

    PageBackdropBox {
        Scaffold(
            topBar = {
                BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                    AdaptiveTopAppBar(
                        title = stringResource(R.string.external_control_title),
                        scrollBehavior = scrollBehavior,
                        navigationIcon = { BackNavigationIcon(onBack) },
                    )
                }
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                    .horizontalCutoutPadding()
                    .scrollEndHaptic()
                    .overScrollVertical(scrollBehavior)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                ),
            ) {
                item { RestartRequiredHint() }

                item {
                    Spacer(Modifier.height(6.dp))
                }

                groupedCardItems(
                    keyPrefix = "external_control",
                    outerBottomPadding = 12.dp,
                    items = listOf(
                        CardItem("controller") {
                            ArrowPreference(
                                title = stringResource(R.string.external_control_ip),
                                summary = uiState.externalController ?: notModifiedStr,
                                onClick = {
                                    controllerTextState.edit {
                                        replace(0, length, uiState.externalController ?: "")
                                    }
                                    showEditDialog = true
                                },
                            )
                        },
                        CardItem("secret") {
                            ArrowPreference(
                                title = stringResource(R.string.external_control_secret),
                                summary = uiState.secret ?: notModifiedStr,
                                onClick = {
                                    secretTextState.edit {
                                        replace(0, length, uiState.secret ?: "")
                                    }
                                    showSecretDialog = true
                                },
                            )
                        },
                    ),
                )

                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.external_control_controller_hint),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                        Text(
                            text = stringResource(R.string.external_control_secret_hint),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }

                item {
                    Spacer(
                        Modifier
                            .height(24.dp)
                            .navigationBarsPadding()
                    )
                }
            }
        }

        GlassDialog(
            show = showEditDialog,
            backdrop = LocalPageBackdrop.current,
            title = stringResource(R.string.network_external_controller),
            onDismissRequest = { showEditDialog = false },
        ) {
            TextField(
                state = controllerTextState,
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.network_input_value),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlassDialogTextButton(
                    text = stringResource(R.string.common_not_modified),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        viewModel.setExternalController(null)
                        showToast(resetDoneMsg)
                        showEditDialog = false
                    },
                )
                GlassDialogTextButton(
                    text = stringResource(R.string.common_cancel),
                    modifier = Modifier.weight(1f),
                    onClick = { showEditDialog = false },
                )
                GlassDialogTextButton(
                    text = stringResource(R.string.common_confirm),
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Primary,
                    onClick = {
                        val value = controllerTextState.text.toString().trim().takeIf { it.isNotEmpty() }
                        viewModel.setExternalController(value)
                        showEditDialog = false
                    },
                )
            }
        }

        GlassDialog(
            show = showSecretDialog,
            backdrop = LocalPageBackdrop.current,
            title = stringResource(R.string.external_control_secret),
            onDismissRequest = { showSecretDialog = false },
        ) {
            TextField(
                state = secretTextState,
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.network_input_value),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlassDialogTextButton(
                    text = stringResource(R.string.common_not_modified),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        viewModel.setSecret(null)
                        showToast(resetDoneMsg)
                        showSecretDialog = false
                    },
                )
                GlassDialogTextButton(
                    text = stringResource(R.string.common_cancel),
                    modifier = Modifier.weight(1f),
                    onClick = { showSecretDialog = false },
                )
                GlassDialogTextButton(
                    text = stringResource(R.string.common_confirm),
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Primary,
                    onClick = {
                        val value = secretTextState.text.toString().trim().takeIf { it.isNotEmpty() }
                        viewModel.setSecret(value)
                        showSecretDialog = false
                    },
                )
            }
        }
    }
}
