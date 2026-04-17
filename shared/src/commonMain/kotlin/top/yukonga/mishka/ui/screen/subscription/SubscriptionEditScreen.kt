package top.yukonga.mishka.ui.screen.subscription

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import mishka.shared.generated.resources.common_save
import mishka.shared.generated.resources.subscription_auto_update
import mishka.shared.generated.resources.subscription_auto_update_placeholder
import mishka.shared.generated.resources.subscription_config_name
import mishka.shared.generated.resources.subscription_edit
import mishka.shared.generated.resources.subscription_sub_url
import org.jetbrains.compose.resources.stringResource
import top.yukonga.mishka.data.model.ProfileType
import top.yukonga.mishka.viewmodel.SubscriptionViewModel
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 订阅编辑页面 —— 编辑名称、URL、自动更新间隔
 */
@Composable
fun SubscriptionEditScreen(
    uuid: String,
    viewModel: SubscriptionViewModel,
    onBack: () -> Unit = {},
    onSaved: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val subscription = uiState.subscriptions.find { it.id == uuid }
    val scrollBehavior = MiuixScrollBehavior()

    var name by remember(subscription) { mutableStateOf(subscription?.name ?: "") }
    var url by remember(subscription) { mutableStateOf(subscription?.url ?: "") }
    var intervalMinutes by remember(subscription) {
        mutableStateOf(
            if ((subscription?.interval ?: 0) > 0) ((subscription?.interval ?: 0) / 60000).toString() else ""
        )
    }

    if (subscription == null) {
        onBack()
        return
    }

    val isFile = subscription.type == ProfileType.File
    val hasChanges = name != subscription.name ||
            url != subscription.url ||
            intervalMinutes != (if (subscription.interval > 0) (subscription.interval / 60000).toString() else "")

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(Res.string.subscription_edit),
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
                bottom = 24.dp,
            ),
        ) {
            item {
                SmallTitle(text = stringResource(Res.string.subscription_config_name))
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 6.dp),
                )
                if (!isFile) {
                    SmallTitle(text = stringResource(Res.string.subscription_sub_url))
                    TextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 6.dp),
                    )
                    SmallTitle(text = stringResource(Res.string.subscription_auto_update))
                    TextField(
                        value = intervalMinutes,
                        onValueChange = { intervalMinutes = it.filter { c -> c.isDigit() } },
                        label = stringResource(Res.string.subscription_auto_update_placeholder),
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

            item {
                TextButton(
                    text = stringResource(Res.string.common_save),
                    onClick = {
                        val intervalMs = (intervalMinutes.toLongOrNull() ?: 0) * 60000
                        viewModel.editSubscription(
                            uuid = uuid,
                            name = name,
                            source = url,
                            interval = intervalMs,
                            onComplete = onSaved,
                        )
                    },
                    enabled = hasChanges && !uiState.isLoading && name.isNotBlank(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = 6.dp, bottom = 16.dp + innerPadding.calculateBottomPadding()),
                )
            }
        }
    }
}
