package top.yukonga.mishka.ui.util

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import top.yukonga.mishka.ui.component.blur.LocalTopBarStartInset
import top.yukonga.mishka.ui.theme.LocalContainerSize
import top.yukonga.mishka.ui.theme.LocalPlatformDensity
import top.yukonga.miuix.kmp.anim.folmeSpring

val LocalHorizontalCutoutSides = staticCompositionLocalOf { WindowInsetsSides.Horizontal }

@Immutable
data class HorizontalSafeInsets(
    val start: Dp,
    val end: Dp,
)

@Composable
fun rememberHorizontalSafeInsets(): HorizontalSafeInsets {
    val layoutDirection = LocalLayoutDirection.current
    val padding = WindowInsets.displayCutout.union(WindowInsets.navigationBars).asPaddingValues()
    return HorizontalSafeInsets(
        start = padding.calculateStartPadding(layoutDirection),
        end = padding.calculateEndPadding(layoutDirection),
    )
}


val DualPaneMasterMinWidth = 360.dp
val DualPaneMasterMaxWidth = 400.dp
val DualPaneDetailMinWidth = 400.dp
val DualPaneDividerWidth = 1.dp
val DualPaneMinWidth = DualPaneMasterMinWidth + DualPaneDividerWidth + DualPaneDetailMinWidth

fun calculateDualPaneMasterWidth(availableWidth: Dp): Dp =
    (availableWidth * 0.36f).coerceIn(DualPaneMasterMinWidth, DualPaneMasterMaxWidth)

/** 宽屏阈值：窗口宽度达到此值时启用侧边导航栏与内容居中（平板、横屏、展开态折叠屏、桌面）。 */
private val WideScreenMinWidth = 600.dp

/** 当前窗口是否达到宽屏尺寸。宽屏切换到 NavigationRail + 固定小标题栏 + 内容居中。 */
@Composable
fun rememberIsWideScreen(): Boolean {
    val containerSize = LocalContainerSize.current ?: LocalWindowInfo.current.containerSize
    val density = LocalPlatformDensity.current ?: LocalDensity.current
    return with(density) { containerSize.width.toDp() >= WideScreenMinWidth }
}

/**
 * 为内容补上水平方向（起始 + 末尾）的屏幕缺口 / 侧边导航栏 inset padding。二级页 Scaffold 内容默认
 * 只吃了顶部 inset，横屏遇到侧边刘海 / 挖孔 / 手势条时内容会压到缺口下——在内容根容器加此 modifier
 * 让内容避开。竖屏或无侧边缺口时 inset 为 0、等同无操作。顶栏由 `TopAppBar` 自己的
 * `defaultWindowInsetsPadding` 处理，与此互不重叠。
 */
@Composable
fun Modifier.sidebarStartInset(): Modifier {
    val startInset = LocalTopBarStartInset.current
    return layout { measurable, constraints ->
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
}

@Composable
fun Modifier.horizontalCutoutPadding(applySidebarInset: Boolean = true): Modifier = this
    .windowInsetsPadding(
        WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(LocalHorizontalCutoutSides.current),
    )
    .then(if (applySidebarInset) Modifier.sidebarStartInset() else Modifier)

/**
 * BottomSheet 内容的底部安全区。miuix 的 sheet 只装了 `imePadding()`，`captionBar`（小窗 / 桌面窗口模式的系统
 * 标题栏）与 `displayCutout` 仅取 top 分量算最大高度上限、不产生 padding，底部因此要内容自补。取
 * [WindowInsets.systemBars]（含 statusBars / navigationBars / captionBar）∪ [WindowInsets.displayCutout] 的底部。
 *
 * 两处刻意的排除，勿「顺手补全」：
 * - **不含 ime**：sheet 已装 `imePadding()`，并进来会叠成双倍底距。
 * - **不含水平方向**：sheet `BottomCenter` 对齐且 `widthIn(max = 640.dp)`，水平 inset 只在有缺口的单侧生效，
 *   会把居中的内容推偏；窗口更宽时两侧本就留白、缺口也压不到。
 */
@Composable
fun Modifier.sheetContentSafePadding(): Modifier = windowInsetsPadding(
    WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Bottom),
)

/**
 * BottomSheet 内容的高度过渡。sheet 是 `wrapContentHeight`、高度完全跟随内容且自身无动画，
 * loading 切列表、条数增减都会整体硬跳。
 *
 * spec 与 sheet 的入场动画同参（[folmeSpring] 是 miuix public API，别自己凑 spring 参数）。
 */
fun Modifier.sheetHeightTransition(): Modifier = animateContentSize(
    folmeSpring(damping = 0.9f, response = 0.38f, visibilityThreshold = IntSize(1, 1)),
)
