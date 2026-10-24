package top.yukonga.mishka.ui.screen.provider

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.mishka.R
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.util.formatIsoTimeAsLocalShort
import top.yukonga.mishka.viewmodel.ProviderErrorKey
import top.yukonga.mishka.viewmodel.ProviderItemUi
import top.yukonga.mishka.viewmodel.ProviderViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun ProviderScreen(
    viewModel: ProviderViewModel,
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()
    var selectedTabIndex by rememberSaveable("provider_type_tab") { mutableIntStateOf(RULE_PROVIDERS_TAB) }
    val selectedIsRuleProvider = selectedTabIndex == RULE_PROVIDERS_TAB
    val visibleProviders = uiState.providers.filter { it.isRuleProvider == selectedIsRuleProvider }
    // 非当前 Tab 的更新错误没有行内 banner 可见，聚合到列表顶部的汇总卡展示
    val otherTabErrors = uiState.providerErrors.entries
        .filter { it.key.isRuleProvider != selectedIsRuleProvider }
        .sortedBy { it.key.name }
    val providerTabs = listOf(
        stringResource(R.string.provider_rule_providers),
        stringResource(R.string.provider_proxy_providers),
    )
    val providerTabsContent: @Composable () -> Unit = {
        TabRow(
            tabs = providerTabs,
            selectedTabIndex = selectedTabIndex,
            onTabSelected = { selectedTabIndex = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        )
    }
    val otherTabErrorsContent: @Composable () -> Unit = {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                otherTabErrors.forEach { (errorKey, message) ->
                    Text(
                        text = stringResource(R.string.provider_update_failed, errorKey.name, message),
                        fontSize = 12.sp,
                        color = StatusColors.danger,
                    )
                }
            }
        }
    }

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.provider_title),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            val layoutDirection = LocalLayoutDirection.current
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.common_back),
                                tint = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                                },
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.updateAll() }) {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = stringResource(R.string.subscription_update_all),
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
            ),
        ) {
            if (visibleProviders.isEmpty() && !uiState.isLoading) {
                item(key = "empty", contentType = "empty") {
                    Column(
                        modifier = Modifier.fillParentMaxSize(),
                    ) {
                        Spacer(Modifier.height(12.dp))
                        providerTabsContent()
                        if (otherTabErrors.isNotEmpty()) {
                            otherTabErrorsContent()
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = stringResource(R.string.provider_no_providers),
                                    fontSize = 16.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                // 仅当两个 Tab 都没有 provider 时才提示启动服务；
                                // 当前 Tab 为空但另一 Tab 有 provider 说明服务已在运行
                                if (uiState.providers.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.provider_start_first),
                                        modifier = Modifier.padding(top = 6.dp),
                                        fontSize = 14.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                item(key = "top_spacer", contentType = "spacer") {
                    Spacer(Modifier.height(12.dp))
                }
                item(key = "provider_type_tabs", contentType = "tabs") {
                    providerTabsContent()
                }
                if (otherTabErrors.isNotEmpty()) {
                    item(key = "other_tab_errors", contentType = "error_summary") {
                        otherTabErrorsContent()
                    }
                }
                items(
                    items = visibleProviders,
                    key = { "${it.isRuleProvider}:${it.name}" },
                    contentType = { "provider" },
                ) { provider ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ProviderItem(
                            provider = provider,
                            error = uiState.providerErrors[
                                ProviderErrorKey(provider.name, provider.isRuleProvider)
                            ],
                            onUpdate = {
                                viewModel.updateProvider(provider.name, provider.isRuleProvider)
                            },
                        )
                    }
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

    ProviderRefreshDialog(
        show = uiState.refresh != null,
        progress = uiState.refresh,
    )
}

private const val RULE_PROVIDERS_TAB = 0

@Composable
private fun ProviderItem(
    provider: ProviderItemUi,
    error: String?,
    onUpdate: () -> Unit,
) {
    Column {
        BasicComponent(
            title = provider.name,
            summary = stringResource(
                if (provider.isRuleProvider) R.string.provider_kind_rule else R.string.provider_kind_proxy,
                provider.type,
            ),
            endActions = {
                if (provider.vehicleType != "Inline") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = formatIsoTimeAsLocalShort(provider.updatedAt),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Image(
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                    onClick = onUpdate,
                                ),
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = stringResource(R.string.provider_update),
                            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
                        )
                    }
                }
            },
        )
        // 退出动画期间 error 已置 null，保留最后一条非空错误供动画帧渲染，
        // 避免闪现空原因文案并触发 liveRegion 播报
        var lastError by remember { mutableStateOf(error) }
        if (error != null) lastError = error
        AnimatedVisibility(
            visible = error != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Text(
                text = stringResource(R.string.provider_update_failed, provider.name, lastError.orEmpty()),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
                fontSize = 12.sp,
                color = StatusColors.danger,
            )
        }
    }
}
