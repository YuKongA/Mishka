package top.yukonga.mishka.ui.screen.provider

import androidx.compose.animation.CapturedAnimatedVisibility
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.computedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import top.yukonga.mishka.R
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.BackNavigationIcon
import top.yukonga.mishka.ui.component.TopBarIconButton
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.PageBackdropBox
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.overScrollVertical
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.util.formatIsoTimeAsLocalShort
import top.yukonga.mishka.viewmodel.ProviderErrorKey
import top.yukonga.mishka.viewmodel.ProviderItemUi
import top.yukonga.mishka.viewmodel.ProviderViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassSegmentedTabRow
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.utils.springAnimateToPage

@Composable
fun ProviderScreen(
    viewModel: ProviderViewModel,
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()
    val providerTabs = listOf(
        stringResource(R.string.provider_rule_providers),
        stringResource(R.string.provider_proxy_providers),
    )
    val pagerState = rememberPagerState(initialPage = RULE_PROVIDERS_TAB, pageCount = { providerTabs.size })
    val coroutineScope = rememberCoroutineScope()
    val listStates = remember { List(providerTabs.size) { LazyListState() } }
    val contentUnderTopBar by remember(listStates, pagerState, scrollBehavior) {
        computedStateOf {
            listStates[pagerState.currentPage].canScrollBackward || scrollBehavior.state.overscrollOffset < -1f
        }
    }
    val otherTabErrorsContent: @Composable (List<Map.Entry<ProviderErrorKey, String>>) -> Unit = { errors ->
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
                errors.forEach { (errorKey, message) ->
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
    val fallbackBackdrop = rememberLayerBackdrop()

    PageBackdropBox {
        Scaffold(
            topBar = {
                BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                    AdaptiveTopAppBar(
                        title = stringResource(R.string.provider_title),
                        scrollBehavior = scrollBehavior,
                        isContentScrolled = contentUnderTopBar,
                        navigationIcon = { BackNavigationIcon(onBack) },
                        actions = {
                            TopBarIconButton(
                                onClick = { viewModel.updateAll() },
                                glassIcon = MiuixIcons.Glass.Refresh,
                                contentDescription = stringResource(R.string.subscription_update_all),
                            )
                        },
                        bottomContent = {
                            GlassSegmentedTabRow(
                                tabs = providerTabs,
                                selectedIndex = pagerState.targetPage,
                                onSelect = { index ->
                                    coroutineScope.launch { pagerState.springAnimateToPage(index) }
                                },
                                backdrop = backdrop ?: fallbackBackdrop,
                                surfaceAlpha = if (blurActive && contentUnderTopBar) 1f else 0f,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .padding(top = 6.dp, bottom = 12.dp),
                            )
                        },
                    )
                }
            },
        ) { innerPadding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
            ) { page ->
                val pageIsRuleProvider = page == RULE_PROVIDERS_TAB
                val visibleProviders = uiState.providers.filter { it.isRuleProvider == pageIsRuleProvider }
                val otherTabErrors = uiState.providerErrors.entries
                    .filter { it.key.isRuleProvider != pageIsRuleProvider }
                    .sortedBy { it.key.name }
                LazyColumn(
                    state = listStates[page],
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalCutoutPadding()
                        .scrollEndHaptic()
                        .overScrollVertical(scrollBehavior)
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
                                if (otherTabErrors.isNotEmpty()) {
                                    otherTabErrorsContent(otherTabErrors)
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
                        if (otherTabErrors.isNotEmpty()) {
                            item(key = "other_tab_errors", contentType = "error_summary") {
                                otherTabErrorsContent(otherTabErrors)
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
                                .navigationBarsPadding(),
                        )
                    }
                }
            }
        }

        ProviderRefreshDialog(
            show = uiState.refresh != null,
            progress = uiState.refresh,
        )
    }
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
                                .size(20.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                    onClick = onUpdate,
                                ),
                            imageVector = MiuixIcons.Glass.Refresh,
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
        CapturedAnimatedVisibility(
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
