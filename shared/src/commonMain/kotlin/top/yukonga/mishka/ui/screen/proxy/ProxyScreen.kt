package top.yukonga.mishka.ui.screen.proxy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.common_more
import mishka.shared.generated.resources.common_refresh
import mishka.shared.generated.resources.proxy_no_groups
import mishka.shared.generated.resources.proxy_refresh_icon
import mishka.shared.generated.resources.proxy_start_first
import mishka.shared.generated.resources.proxy_timeout
import mishka.shared.generated.resources.proxy_title
import org.jetbrains.compose.resources.stringResource
import top.yukonga.mishka.platform.IconLoader
import top.yukonga.mishka.ui.component.ListPopupDefaults.MenuPositionProvider
import top.yukonga.mishka.viewmodel.ProxyGroupUi
import top.yukonga.mishka.viewmodel.ProxyUiState
import top.yukonga.mishka.viewmodel.ProxyViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.miuixShape
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowListPopup

@Composable
fun ProxyScreen(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    viewModel: ProxyViewModel? = null,
) {
    val uiState = viewModel?.uiState?.collectAsState()?.value ?: ProxyUiState()
    val scrollBehavior = MiuixScrollBehavior()
    val groups = uiState.groups

    val showPopup = remember { mutableStateOf(false) }
    var iconCacheVersion by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = stringResource(Res.string.proxy_title),
                scrollBehavior = scrollBehavior,
                actions = {
                    if (groups.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel?.loadProxies() },
                            enabled = !uiState.isTesting,
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = stringResource(Res.string.common_refresh),
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(
                            onClick = { showPopup.value = true },
                            holdDownState = showPopup.value,
                        ) {
                            Icon(
                                imageVector = MiuixIcons.More,
                                contentDescription = stringResource(Res.string.common_more),
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }

                        WindowListPopup(
                            show = showPopup.value,
                            popupPositionProvider = MenuPositionProvider,
                            alignment = PopupPositionProvider.Align.TopEnd,
                            onDismissRequest = { showPopup.value = false },
                        ) {
                            ListPopupColumn {
                                DropdownImpl(
                                    text = stringResource(Res.string.proxy_refresh_icon),
                                    optionSize = 1,
                                    isSelected = false,
                                    index = 0,
                                    onSelectedIndexChange = {
                                        coroutineScope.launch { IconLoader.clear() }
                                        iconCacheVersion++
                                        showPopup.value = false
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (groups.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding(), bottom = bottomPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(Res.string.proxy_no_groups),
                    fontSize = 16.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = stringResource(Res.string.proxy_start_first),
                    modifier = Modifier.padding(top = 6.dp),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        } else {
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
                items(
                    items = groups,
                    key = { it.name },
                    contentType = { "group" },
                ) { group ->
                    var isExpanded by rememberSaveable(group.name) { mutableStateOf(false) }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(top = 12.dp),
                    ) {
                        ProxyGroupHeader(
                            group = group,
                            isExpanded = isExpanded,
                            iconCacheVersion = iconCacheVersion,
                            onToggle = { isExpanded = !isExpanded },
                        )

                        AnimatedVisibility(
                            visible = isExpanded,
                            enter = expandVertically(),
                            exit = shrinkVertically(),
                        ) {
                            ProxyNodeGrid(
                                group = group,
                                onSelect = { proxyName ->
                                    if (group.type.lowercase() == "selector") {
                                        viewModel?.selectProxy(group.name, proxyName)
                                    }
                                },
                            )
                        }
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(Modifier.padding(bottom = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun ProxyGroupHeader(
    group: ProxyGroupUi,
    isExpanded: Boolean,
    iconCacheVersion: Int,
    onToggle: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(300),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧图标
        GroupIcon(
            icon = group.icon,
            name = group.name,
            cacheVersion = iconCacheVersion,
        )

        Spacer(Modifier.width(12.dp))

        // 中间：组名 + 当前节点
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (group.now.isNotEmpty()) {
                Text(
                    text = group.now,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 右侧：当前节点延迟 + 节点数 + 箭头
        val nowDelay = group.delays[group.now]
        if (nowDelay != null) {
            val timeoutText = stringResource(Res.string.proxy_timeout)
            val delayText = if (nowDelay < 0) timeoutText else "${nowDelay}ms"
            val delayColor = when {
                nowDelay < 0 -> Color(0xFFE53935)
                nowDelay < 200 -> Color(0xFF4CAF50)
                nowDelay < 500 -> Color(0xFFFFC107)
                else -> Color(0xFFE53935)
            }
            Text(
                text = delayText,
                fontSize = 12.sp,
                color = delayColor,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = "${group.all.size}",
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(8.dp))
        val layoutDirection = LocalLayoutDirection.current
        Image(
            imageVector = MiuixIcons.Basic.ArrowRight,
            contentDescription = null,
            modifier = Modifier
                .size(width = 10.dp, height = 16.dp)
                .graphicsLayer {
                    scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                }
                .rotate(rotation),
            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
        )
    }
}

@Composable
private fun GroupIcon(
    icon: String,
    name: String,
    cacheVersion: Int,
) {
    if (icon.isNotEmpty()) {
        var bitmap by remember(icon, cacheVersion) { mutableStateOf<ImageBitmap?>(null) }

        LaunchedEffect(icon, cacheVersion) {
            bitmap = IconLoader.loadIcon(icon)
        }

        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = name,
                modifier = Modifier
                    .size(36.dp)
                    .clip(miuixShape(8.dp)),
            )
        } else {
            DefaultGroupIcon(name)
        }
    } else {
        DefaultGroupIcon(name)
    }
}

@Composable
private fun DefaultGroupIcon(name: String) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(miuixShape(8.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (name.isEmpty()) "" else {
                val first = name[0]
                if (first.isHighSurrogate() && name.length > 1 && name[1].isLowSurrogate()) {
                    name.substring(0, 2)
                } else {
                    first.toString()
                }
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.primary.copy(0.8f),
        )
    }
}

@Composable
private fun ProxyNodeGrid(
    group: ProxyGroupUi,
    onSelect: (String) -> Unit,
) {
    val isDark = isSystemInDarkTheme()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        group.all.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { proxyName ->
                    val isSelected = proxyName == group.now
                    val delay = group.delays[proxyName]
                    val nodeType = group.nodeTypes[proxyName] ?: ""
                    val isSelectable = group.type.lowercase() == "selector"

                    ProxyNodeCard(
                        name = proxyName,
                        type = nodeType,
                        delay = delay,
                        isSelected = isSelected,
                        isSelectable = isSelectable,
                        isDark = isDark,
                        onClick = { onSelect(proxyName) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ProxyNodeCard(
    name: String,
    type: String,
    delay: Int?,
    isSelected: Boolean,
    isSelectable: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeoutStr = stringResource(Res.string.proxy_timeout)
    val delayText = when {
        delay == null -> null
        delay < 0 -> timeoutStr
        else -> "$delay"
    }
    val delayColor = when {
        delay == null -> Color(0xFF9E9E9E)
        delay < 0 -> Color(0xFFE53935)
        delay < 200 -> Color(0xFF4CAF50)
        delay < 500 -> Color(0xFFFFC107)
        else -> Color(0xFFE53935)
    }

    val backgroundColor = if (isSelected) {
        if (isDark) Color(0xFF1A3040) else Color(0xFFE3F2FD)
    } else {
        MiuixTheme.colorScheme.surface
    }

    Box(
        modifier = modifier
            .clip(miuixShape(12.dp))
            .background(backgroundColor)
            .then(
                if (isSelectable) Modifier.clickable(onClick = onClick) else Modifier
            )
            .padding(12.dp),
    ) {
        Column {
            // 第一行：节点名 + 延迟
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (delayText != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = delayText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = delayColor,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // 第二行：协议类型 Badge
            if (type.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(miuixShape(3.dp))
                        .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = type,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}
