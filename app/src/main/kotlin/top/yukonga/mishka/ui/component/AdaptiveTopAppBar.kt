package top.yukonga.mishka.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import top.yukonga.mishka.R
import top.yukonga.mishka.ui.component.blur.LocalBlurEnabled
import top.yukonga.mishka.ui.component.blur.LocalTopAppBarBackdrop
import top.yukonga.mishka.ui.component.blur.LocalTopAppBarTitleAlpha
import top.yukonga.mishka.ui.component.blur.LocalTopBarStartInset
import top.yukonga.mishka.ui.util.rememberIsWideScreen
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical as miuixOverScrollVertical

private fun Modifier.topBarStartInset(startInset: () -> Dp): Modifier = layout { measurable, constraints ->
    val insetPx = startInset().roundToPx().coerceIn(0, constraints.maxWidth)
    if (insetPx == 0) {
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    } else {
        val placeable = measurable.measure(constraints.offset(horizontal = -insetPx))
        layout(placeable.width + insetPx, placeable.height) {
            placeable.placeRelative(insetPx, 0)
        }
    }
}

/**
 * 顶栏自适应：手机默认可折叠大标题，宽屏固定小标题；同时根据 [LocalTopBarStyle]
 * 在 [GlassTopAppBar]（默认）与原版 [TopAppBar] / [SmallTopAppBar] 间切换。
 */
@Composable
fun AdaptiveTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    expandable: Boolean = !rememberIsWideScreen(),
    titleColor: Color = MiuixTheme.colorScheme.onSurface,
    titleAlpha: () -> Float = { titleColor.alpha },
    defaultWindowInsetsPadding: Boolean = true,
    scrollBehavior: ScrollBehavior? = null,
    isContentScrolled: Boolean? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable () -> Unit = {},
) {
    val backdrop = LocalTopAppBarBackdrop.current
    val barTitleAlpha = LocalTopAppBarTitleAlpha.current
    val startInset = LocalTopBarStartInset.current
    val surfaceColor = MiuixTheme.colorScheme.surface
    val bandBrush = remember(surfaceColor) { GlassTopAppBarDefaults.bandBrush(surfaceColor) }
    val resolvedContentScrolled by remember(scrollBehavior, isContentScrolled) {
        derivedStateOf {
            isContentScrolled ?: (scrollBehavior?.state?.let { it.contentOffset < 0f } ?: false)
        }
    }
    GlassTopAppBar(
        title = title,
        isContentScrolled = resolvedContentScrolled,
        modifier = modifier,
        contentModifier = contentModifier.topBarStartInset(startInset),
        expandable = expandable,
        progressiveBlur = LocalBlurEnabled.current,
        backdrop = backdrop,
        scrollBehavior = scrollBehavior,
        bandBrush = bandBrush,
        bandOverhang = GlassTopAppBarDefaults.BandOverhang,
        defaultWindowInsetsPadding = defaultWindowInsetsPadding,
        titleAlpha = { titleAlpha() * barTitleAlpha() },
        navigationIcon = navigationIcon,
        actions = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() } },
        bottomContent = bottomContent,
    )
}

@Composable
fun BackNavigationIcon(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layoutDirection = LocalLayoutDirection.current
    val mirrorModifier = Modifier.graphicsLayer {
        scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
    }
    GlassIconButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        Icon(
            imageVector = MiuixIcons.Glass.ChevronBackward,
            contentDescription = stringResource(R.string.common_back),
            tint = MiuixTheme.colorScheme.onSurface,
            modifier = mirrorModifier.size(28.dp),
        )
    }
}

@Composable
fun TopBarIconButton(
    onClick: () -> Unit,
    glassIcon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
) {
    val tint = if (enabled) {
        MiuixTheme.colorScheme.onSurface
    } else {
        MiuixTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    GlassIconButton(
        onClick = { if (enabled && !isLoading) onClick() },
        modifier = modifier,
    ) {
        if (isLoading) {
            CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = glassIcon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
fun Modifier.overScrollVertical(scrollBehavior: ScrollBehavior): Modifier {
    val state = scrollBehavior.state
    val onOffsetChanged: (Float) -> Unit = remember(state) {
        { state.overscrollOffset = it }
    }
    return miuixOverScrollVertical(onOffsetChanged = onOffsetChanged)
}
