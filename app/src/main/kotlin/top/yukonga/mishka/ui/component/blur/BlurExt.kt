package top.yukonga.mishka.ui.component.blur

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.mishka.ui.theme.TopBarBlurStyle
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

val LocalBlurEnabled = staticCompositionLocalOf { true }
val LocalTopBarBlurStyle = staticCompositionLocalOf { TopBarBlurStyle.Gaussian }
val LocalTopAppBarBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
val LocalPageBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
val LocalTopAppBarTitleAlpha = staticCompositionLocalOf<() -> Float> { { 1f } }
val LocalTopBarStartInset = staticCompositionLocalOf<() -> Dp> { { 0.dp } }

@Composable
fun Modifier.defaultBlurEffect(
    backdrop: LayerBackdrop,
): Modifier = this.textureBlur(
    backdrop = backdrop,
    shape = RectangleShape,
    blurRadius = 25f,
    colors = BlurColors(
        blendColors = listOf(
            BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.8f)),
        ),
    ),
)

@Composable
fun rememberBlurEnabled(): State<Boolean> =
    rememberUpdatedState(LocalBlurEnabled.current && isRuntimeShaderSupported())

@Composable
fun rememberBlurBackdrop(enabled: Boolean = LocalBlurEnabled.current): LayerBackdrop? {
    if (!enabled || !isRuntimeShaderSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

/**
 * 为页面整体提供 [LocalPageBackdrop]（供 GlassDialog / GlassBottomSheet / GlassDropdownPreference /
 * 悬浮玻璃菜单采样页面与顶底栏内容，避免被自身遮罩循环采样）。
 */
@Composable
fun PageBackdropBox(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val pageBackdrop = rememberBlurBackdrop()
    CompositionLocalProvider(LocalPageBackdrop provides pageBackdrop) {
        Box(
            modifier = modifier.then(
                if (pageBackdrop != null) Modifier.layerBackdrop(pageBackdrop) else Modifier,
            ),
        ) {
            content()
        }
    }
}

@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    blurActive: Boolean = rememberBlurEnabled().value,
    blurStyle: TopBarBlurStyle = LocalTopBarBlurStyle.current,
    forceTextureBlur: Boolean = false,
    content: @Composable () -> Unit,
) {
    val drawBlur = blurActive && backdrop != null && forceTextureBlur
    val progressive = blurStyle == TopBarBlurStyle.Progressive
    CompositionLocalProvider(LocalTopAppBarBackdrop provides backdrop) {
        Box(
            modifier = if (drawBlur && !progressive) {
                Modifier.defaultBlurEffect(backdrop)
            } else {
                Modifier
            },
        ) {
            if (drawBlur && progressive) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .progressiveTextureBlur(
                            backdrop = backdrop,
                            shape = RectangleShape,
                            gradient = ProgressiveBlur.Top.copy(curve = 2.2f),
                            blurRadius = 10f,
                            colors = BlurColors(
                                blendColors = listOf(
                                    BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.3f)),
                                ),
                            ),
                        ),
                )
            }
            content()
        }
    }
}
