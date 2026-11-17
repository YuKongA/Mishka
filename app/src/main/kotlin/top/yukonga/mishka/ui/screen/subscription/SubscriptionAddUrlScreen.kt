package top.yukonga.mishka.ui.screen.subscription

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.mishka.R
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.BackNavigationIcon
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.PageBackdropBox
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.overScrollVertical
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.viewmodel.SubscriptionViewModel
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * URL 导入配置页面
 */
@Composable
fun SubscriptionAddUrlScreen(
    viewModel: SubscriptionViewModel,
    initialUrl: String = "",
    initialName: String = "",
    initialIntervalMinutes: Long = 0,
    onBack: () -> Unit = {},
    onSaved: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()
    var inputName by remember { mutableStateOf(initialName) }
    var inputUrl by remember { mutableStateOf(initialUrl) }
    var userAgent by remember { mutableStateOf("") }
    var ageSecretKey by remember { mutableStateOf("") }
    var intervalMinutes by remember {
        mutableStateOf(if (initialIntervalMinutes > 0) initialIntervalMinutes.toString() else "")
    }

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null

    PageBackdropBox {
        Scaffold(
            topBar = {
                BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                    AdaptiveTopAppBar(
                        title = stringResource(R.string.subscription_config),
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
                item(key = "hint") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(top = 12.dp, bottom = 6.dp),
                        insideMargin = PaddingValues(16.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Info,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Text(
                                text = stringResource(R.string.subscription_url_hint),
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
                item(key = "name_title") {
                    SmallTitle(text = stringResource(R.string.subscription_name))
                }
                item(key = "name_field") {
                    TextField(
                        value = inputName,
                        onValueChange = { inputName = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 6.dp),
                        label = stringResource(R.string.subscription_name_auto_placeholder),
                        useLabelAsPlaceholder = true,
                    )
                }
                item(key = "url_title") {
                    SmallTitle(text = stringResource(R.string.subscription_url_label))
                }
                item(key = "url_field") {
                    TextField(
                        value = inputUrl,
                        onValueChange = { inputUrl = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 6.dp),
                        label = stringResource(R.string.subscription_url_placeholder),
                        useLabelAsPlaceholder = true,
                    )
                }
                item(key = "user_agent_title") {
                    SmallTitle(text = stringResource(R.string.subscription_user_agent))
                }
                item(key = "user_agent_field") {
                    TextField(
                        value = userAgent,
                        onValueChange = { userAgent = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 6.dp),
                        label = stringResource(R.string.subscription_user_agent_placeholder),
                        useLabelAsPlaceholder = true,
                    )
                }
                item(key = "age_secret_key_title") {
                    SmallTitle(text = stringResource(R.string.subscription_age_secret_key))
                }
                item(key = "age_secret_key_field") {
                    TextField(
                        value = ageSecretKey,
                        onValueChange = { ageSecretKey = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 6.dp),
                        label = stringResource(R.string.subscription_age_secret_key_placeholder),
                        useLabelAsPlaceholder = true,
                    )
                }
                item(key = "interval_field") {
                    SmallTitle(text = stringResource(R.string.subscription_auto_update))
                    TextField(
                        value = intervalMinutes,
                        onValueChange = { intervalMinutes = it.filter { c -> c.isDigit() } },
                        label = stringResource(R.string.subscription_auto_update_placeholder),
                        useLabelAsPlaceholder = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    )
                }
                item(key = "save") {
                    TextButton(
                        text = stringResource(R.string.common_save),
                        onClick = {
                            val intervalMs = (intervalMinutes.toLongOrNull() ?: 0) * 60000
                            viewModel.addSubscription(
                                name = inputName.trim(),
                                url = inputUrl,
                                interval = intervalMs,
                                userAgent = userAgent.trim(),
                                ageSecretKey = ageSecretKey.trim(),
                                onComplete = onSaved,
                            )
                        },
                        enabled = inputUrl.isNotBlank() && !uiState.isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
                if (uiState.error.isNotEmpty()) {
                    item(key = "error") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
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
                item(key = "bottom_spacer") {
                    Spacer(
                        Modifier
                            .height(24.dp)
                            .navigationBarsPadding()
                    )
                }
            }
        }

        ImportProgressDialog(
            show = uiState.isLoading,
            step = uiState.importProgress?.let { importStepLabel(it) } ?: stringResource(R.string.common_processing),
            onCancel = { viewModel.cancelCurrentUpdate() },
        )
    }
}
