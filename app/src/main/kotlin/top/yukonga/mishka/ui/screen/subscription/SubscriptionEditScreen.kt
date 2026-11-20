package top.yukonga.mishka.ui.screen.subscription

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.mishka.R
import top.yukonga.mishka.domain.model.ProfileType
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.BackNavigationIcon
import top.yukonga.mishka.ui.component.CardItem
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.PageBackdropBox
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.groupedCardItems
import top.yukonga.mishka.ui.component.overScrollVertical
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.viewmodel.SubscriptionViewModel
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private val DigitsOnlyTransformation = InputTransformation {
    if (asCharSequence().any { !it.isDigit() }) revertAllChanges()
}

/**
 * 订阅编辑页面 —— 编辑名称、URL、自动更新间隔
 */
@Composable
fun SubscriptionEditScreen(
    uuid: String,
    viewModel: SubscriptionViewModel,
    onBack: () -> Unit = {},
    onSaved: () -> Unit = {},
    onNavigateOverrides: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val subscription = uiState.subscriptions.find { it.id == uuid }
    val scrollBehavior = MiuixScrollBehavior()

    val nameState = key(uuid) { rememberTextFieldState(initialText = subscription?.name ?: "") }
    val urlState = key(uuid) { rememberTextFieldState(initialText = subscription?.url ?: "") }
    val userAgentState = key(uuid) { rememberTextFieldState(initialText = subscription?.userAgent ?: "") }
    val ageSecretKeyState = key(uuid) { rememberTextFieldState(initialText = subscription?.ageSecretKey ?: "") }
    val intervalState = key(uuid) {
        rememberTextFieldState(
            initialText = if ((subscription?.interval ?: 0) > 0) ((subscription?.interval ?: 0) / 60000).toString() else "",
        )
    }
    val name = nameState.text.toString()
    val url = urlState.text.toString()
    val userAgent = userAgentState.text.toString()
    val ageSecretKey = ageSecretKeyState.text.toString()
    val intervalMinutes = intervalState.text.toString()

    if (subscription == null) {
        onBack()
        return
    }

    val isFile = subscription.type == ProfileType.File
    val hasChanges = name != subscription.name ||
            url != subscription.url ||
            userAgent.trim() != subscription.userAgent ||
            ageSecretKey.trim() != subscription.ageSecretKey ||
            intervalMinutes != (if (subscription.interval > 0) (subscription.interval / 60000).toString() else "")

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null

    PageBackdropBox {
        Scaffold(
            topBar = {
                BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                    AdaptiveTopAppBar(
                        title = stringResource(R.string.subscription_edit),
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
                item {
                    SmallTitle(text = stringResource(R.string.subscription_config_name))
                    TextField(
                        state = nameState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 6.dp),
                    )
                    if (!isFile) {
                        SmallTitle(text = stringResource(R.string.subscription_sub_url))
                        TextField(
                            state = urlState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 6.dp),
                        )
                        SmallTitle(text = stringResource(R.string.subscription_user_agent))
                        TextField(
                            state = userAgentState,
                            label = stringResource(R.string.subscription_user_agent_placeholder),
                            useLabelAsPlaceholder = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 6.dp),
                        )
                        SmallTitle(text = stringResource(R.string.subscription_age_secret_key))
                        TextField(
                            state = ageSecretKeyState,
                            label = stringResource(R.string.subscription_age_secret_key_placeholder),
                            useLabelAsPlaceholder = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 6.dp),
                        )
                        SmallTitle(text = stringResource(R.string.subscription_auto_update))
                        TextField(
                            state = intervalState,
                            inputTransformation = DigitsOnlyTransformation,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            label = stringResource(R.string.subscription_auto_update_placeholder),
                            useLabelAsPlaceholder = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 6.dp),
                        )
                    }
                }

                if (uiState.error.isNotEmpty()) {
                    item(key = "error") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 6.dp),
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

                groupedCardItems(
                    keyPrefix = "subscription_overrides",
                    outerTopPadding = 6.dp,
                    outerBottomPadding = 12.dp,
                    items = listOf(
                        CardItem("open") {
                            ArrowPreference(
                                title = stringResource(R.string.subscription_overrides),
                                summary = if (subscription.overrideIds.isEmpty()) {
                                    stringResource(R.string.subscription_override_none)
                                } else {
                                    pluralStringResource(
                                        R.plurals.subscription_override_count,
                                        subscription.overrideIds.size,
                                        subscription.overrideIds.size,
                                    )
                                },
                                onClick = onNavigateOverrides,
                            )
                        },
                    ),
                )

                item {
                    TextButton(
                        text = stringResource(R.string.common_save),
                        onClick = {
                            val intervalMs = (intervalMinutes.toLongOrNull() ?: 0) * 60000
                            viewModel.editSubscription(
                                uuid = uuid,
                                name = name,
                                source = url,
                                interval = intervalMs,
                                userAgent = userAgent.trim(),
                                ageSecretKey = ageSecretKey.trim(),
                                onComplete = onSaved,
                            )
                        },
                        enabled = hasChanges && !uiState.isLoading && name.isNotBlank(),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    )
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
    }
}
