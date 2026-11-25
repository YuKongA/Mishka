package top.yukonga.mishka.ui.screen.subscription

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.persistentListOf
import top.yukonga.mishka.R
import top.yukonga.mishka.domain.model.ProfileType
import top.yukonga.mishka.domain.model.Subscription
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.GlassDialogTextButton
import top.yukonga.mishka.ui.component.TopBarIconButton
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.LocalPageBackdrop
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.overScrollVertical
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.util.FormatUtils
import top.yukonga.mishka.util.formatEpochMillisAsLocal
import top.yukonga.mishka.viewmodel.ProfileOperation
import top.yukonga.mishka.viewmodel.SubscriptionUiState
import top.yukonga.mishka.viewmodel.SubscriptionViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Delete
import top.yukonga.miuix.kmp.icon.glass.MoreCircle
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.icon.glass.Add as GlassAdd
import top.yukonga.miuix.kmp.icon.glass.Refresh as GlassRefresh

/**
 * 订阅列表页面
 */
@Composable
fun SubscriptionScreen(
    viewModel: SubscriptionViewModel,
    bottomPadding: Dp = 0.dp,
    onNavigateAdd: () -> Unit = {},
    onNavigateEdit: (uuid: String) -> Unit = {},
    onActiveChanged: (() -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SubscriptionScreenContent(
        uiState = uiState,
        bottomPadding = bottomPadding,
        onUpdateAll = { viewModel.updateAllSubscriptions() },
        onNavigateAdd = { viewModel.clearError(); onNavigateAdd() },
        onSelect = { id ->
            if (viewModel.setActive(id)) {
                onActiveChanged?.invoke()
            }
        },
        onRefresh = { viewModel.fetchSubscription(it) },
        onDelete = { id ->
            viewModel.removeSubscription(id) { onActiveChanged?.invoke() }
        },
        onEdit = onNavigateEdit,
        onCancelUpdate = { viewModel.cancelCurrentUpdate() },
    )
}

@Composable
private fun SubscriptionScreenContent(
    uiState: SubscriptionUiState = SubscriptionUiState(),
    bottomPadding: Dp = 0.dp,
    onUpdateAll: () -> Unit = {},
    onNavigateAdd: () -> Unit = {},
    onSelect: (String) -> Unit = {},
    onRefresh: (String) -> Unit = {},
    onDelete: (String) -> Unit = {},
    onEdit: (String) -> Unit = {},
    onCancelUpdate: () -> Unit = {},
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.subscription_title),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {},
                    actions = {
                        if (uiState.subscriptions.any { it.url.isNotBlank() }) {
                            TopBarIconButton(
                                onClick = onUpdateAll,
                                enabled = !uiState.isLoading,
                                glassIcon = MiuixIcons.Glass.GlassRefresh,
                                contentDescription = stringResource(R.string.subscription_update_all),
                            )
                        }
                        TopBarIconButton(
                            onClick = onNavigateAdd,
                            glassIcon = MiuixIcons.Glass.GlassAdd,
                            contentDescription = stringResource(R.string.subscription_add),
                        )
                    },
                )
            }
        },
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 320.dp),
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .horizontalCutoutPadding()
                .scrollEndHaptic()
                .overScrollVertical(scrollBehavior)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = bottomPadding + 12.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (uiState.error.isNotEmpty()) {
                item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        insideMargin = PaddingValues(16.dp),
                    ) {
                        Text(
                            text = uiState.error,
                            color = StatusColors.danger,
                        )
                    }
                }
            }

            if (uiState.subscriptions.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 120.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.subscription_no_config),
                            fontSize = 16.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            text = stringResource(R.string.subscription_tap_add),
                            modifier = Modifier.padding(top = 6.dp),
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            } else {
                items(uiState.subscriptions, key = { it.id }) { sub ->
                    SubscriptionItem(
                        subscription = sub,
                        isLoading = uiState.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                        onSelect = { onSelect(sub.id) },
                        onRefresh = { onRefresh(sub.id) },
                        onDelete = { onDelete(sub.id) },
                        onEdit = { onEdit(sub.id) },
                    )
                }
            }
        }
    }

    // 批量更新优先展示（含 currentName + (done/total) + step）；
    // 单条走 importProgress，标题由 operation 区分（列表页触发的是 Update）。
    val updateAll = uiState.updateAll
    if (updateAll != null) {
        val progressText = stringResource(
            R.string.subscription_updating_progress,
            updateAll.currentName,
            updateAll.completed + 1,
            updateAll.total,
        )
        val step = updateAll.currentStep?.let { "$progressText\n${importStepLabel(it)}" } ?: progressText
        ImportProgressDialog(
            show = true,
            step = step,
            title = stringResource(R.string.subscription_updating_title),
            onCancel = onCancelUpdate,
        )
    } else {
        val title = when (uiState.operation) {
            ProfileOperation.Update -> stringResource(R.string.subscription_update_config)
            else -> stringResource(R.string.subscription_import_config)
        }
        ImportProgressDialog(
            show = uiState.importProgress != null,
            step = uiState.importProgress?.let { importStepLabel(it) } ?: stringResource(R.string.common_processing),
            title = title,
            onCancel = onCancelUpdate,
        )
    }
}

@Composable
private fun SubscriptionItem(
    subscription: Subscription,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val displayName = subscription.name.ifBlank { stringResource(R.string.subscription_config) }

    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
        onClick = onSelect,
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = subscription.name.ifBlank { stringResource(R.string.subscription_config) },
                modifier = Modifier.weight(1f),
                fontSize = 17.sp,
                fontWeight = FontWeight(550),
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (subscription.isActive) {
                val activeColor = StatusColors.healthy
                Text(
                    text = stringResource(R.string.subscription_in_use),
                    fontSize = 12.sp,
                    fontWeight = FontWeight(750),
                    color = activeColor,
                    modifier = Modifier
                        .squircleBackground(activeColor.copy(alpha = 0.15f), 6.dp)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
            ) {
                if (subscription.total > 0) {
                    val used = subscription.upload + subscription.download
                    Text(
                        text = stringResource(
                            R.string.subscription_used_traffic,
                            FormatUtils.formatBytes(used),
                            FormatUtils.formatBytes(subscription.total)
                        ),
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.subscription_no_traffic),
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                if (subscription.updatedAt > 0) {
                    Text(
                        text = stringResource(R.string.subscription_updated_at, formatEpochMillisAsLocal(subscription.updatedAt)),
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }


            IconButton(
                onClick = onEdit,
                minHeight = 35.dp,
                minWidth = 35.dp,
                backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = MiuixIcons.Glass.MoreCircle,
                    contentDescription = stringResource(R.string.common_edit),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onRefresh,
                enabled = !isLoading && subscription.url.isNotBlank(),
                minHeight = 35.dp,
                minWidth = 35.dp,
                backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = MiuixIcons.Glass.Refresh,
                    contentDescription = stringResource(R.string.common_update),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { showDeleteDialog = true },
                minHeight = 35.dp,
                minWidth = 35.dp,
                backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = MiuixIcons.Glass.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }

    GlassDialog(
        show = showDeleteDialog,
        backdrop = LocalPageBackdrop.current,
        title = stringResource(R.string.subscription_delete_title),
        summary = stringResource(R.string.subscription_delete_summary, displayName),
        onDismissRequest = { showDeleteDialog = false },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassDialogTextButton(
                text = stringResource(R.string.common_cancel),
                modifier = Modifier.weight(1f),
                onClick = { showDeleteDialog = false },
            )
            GlassDialogTextButton(
                text = stringResource(R.string.common_confirm),
                modifier = Modifier.weight(1f),
                role = GlassDialogButtonRole.Primary,
                onClick = {
                    showDeleteDialog = false
                    onDelete()
                },
            )
        }
    }
}

private fun sampleSubscriptionUiState(): SubscriptionUiState = SubscriptionUiState(
    subscriptions = persistentListOf(
        Subscription(
            id = "sub-1",
            name = "ATRI",
            type = ProfileType.Url,
            url = "https://example.com/sub",
            upload = 31L * 1024 * 1024 * 1024,
            download = 120L * 1024 * 1024 * 1024,
            total = 1000L * 1024 * 1024 * 1024,
            updatedAt = 1792575180000L,
            isActive = true,
            imported = true,
        ),
        Subscription(
            id = "sub-2",
            name = "Backup Airport",
            type = ProfileType.Url,
            url = "https://example.com/backup",
            upload = 5L * 1024 * 1024 * 1024,
            download = 25L * 1024 * 1024 * 1024,
            total = 200L * 1024 * 1024 * 1024,
            updatedAt = 1792488780000L,
            isActive = false,
            imported = true,
        ),
    ),
)

@Preview(showBackground = true)
@Composable
private fun SubscriptionScreenPreview() {
    MiuixTheme {
        SubscriptionScreenContent(uiState = sampleSubscriptionUiState())
    }
}

@Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
private fun SubscriptionScreenTabletPreview() {
    MiuixTheme {
        SubscriptionScreenContent(uiState = sampleSubscriptionUiState())
    }
}
