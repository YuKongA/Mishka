package top.yukonga.mishka.ui.screen.provider

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.common_back
import mishka.shared.generated.resources.provider_no_providers
import mishka.shared.generated.resources.provider_start_first
import mishka.shared.generated.resources.provider_title
import mishka.shared.generated.resources.provider_update
import mishka.shared.generated.resources.subscription_update_all
import org.jetbrains.compose.resources.stringResource
import top.yukonga.mishka.viewmodel.ProviderItemUi
import top.yukonga.mishka.viewmodel.ProviderViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
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
    bottomPadding: Dp = 0.dp,
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(Res.string.provider_title),
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
                actions = {
                    IconButton(onClick = { viewModel.updateAll() }) {
                        Icon(
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = stringResource(Res.string.subscription_update_all),
                            tint = MiuixTheme.colorScheme.onSurface,
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
                bottom = bottomPadding,
            ),
        ) {
            if (uiState.error.isNotEmpty()) {
                item(key = "error") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(top = 12.dp, bottom = 6.dp),
                        insideMargin = PaddingValues(16.dp),
                    ) {
                        Text(
                            text = uiState.error,
                            color = Color(0xFFE53935),
                        )
                    }
                }
            }

            if (uiState.providers.isEmpty() && !uiState.isLoading) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier.fillParentMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(Res.string.provider_no_providers),
                            fontSize = 16.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            text = stringResource(Res.string.provider_start_first),
                            modifier = Modifier.padding(top = 6.dp),
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            if (uiState.providers.isNotEmpty()) {
                item(key = "top_spacer", contentType = "spacer") {
                    Spacer(Modifier.height(12.dp))
                }
                items(
                    items = uiState.providers,
                    key = { it.name },
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
                            onUpdate = {
                                val isRule = provider.type.startsWith("规则")
                                viewModel.updateProvider(provider.name, isRule)
                            },
                        )
                    }
                }
            }

            item(key = "bottom_spacer") {
                androidx.compose.foundation.layout.Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun ProviderItem(
    provider: ProviderItemUi,
    onUpdate: () -> Unit,
) {
    BasicComponent(
        title = provider.name,
        summary = provider.type,
        endActions = {
            // Inline 类型不显示更新时间和按钮（同 CMFA）
            if (provider.vehicleType != "Inline") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = formatUpdatedAt(provider.updatedAt),
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
                        contentDescription = stringResource(Res.string.provider_update),
                        colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    )
                }
            }
        },
    )
}

private fun formatUpdatedAt(isoTime: String): String {
    if (isoTime.isBlank()) return ""
    return try {
        // Go 零值时间 "0001-01-01T00:00:00Z" 表示未更新
        if (isoTime.startsWith("0001-")) return ""

        // mihomo 返回 ISO 8601 格式如 "2025-04-14T16:51:30.123456+08:00"
        // 提取为 "04-14 16:51"
        val dateTime = isoTime.substringBefore(".").substringBefore("+").substringBefore("Z")
        val parts = dateTime.split("T")
        if (parts.size == 2) {
            val datePart = parts[0].substringAfter("-") // "04-14"
            val timePart = parts[1].substringBeforeLast(":") // "16:51"
            "$datePart $timePart"
        } else {
            isoTime
        }
    } catch (_: Exception) {
        isoTime
    }
}
