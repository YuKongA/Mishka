package top.yukonga.mishka.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import top.yukonga.mishka.R
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.utils.PagerInterceptionMode

// 主题枚举 → 用户可见名称的唯一映射，避免多处 when 漂移

/** colorMode 的取值，顺序即选项列表顺序：0 跟随系统 / 1 浅色 / 2 深色 */
val ThemeColorModes = listOf(0, 1, 2)

@Composable
fun themeColorModeLabel(colorMode: Int): String = stringResource(
    when (colorMode) {
        1 -> R.string.settings_theme_light
        2 -> R.string.settings_theme_dark
        else -> R.string.settings_theme_system
    },
)

@Composable
fun ThemeAccentColor.label(): String = stringResource(
    when (this) {
        ThemeAccentColor.Default -> R.string.settings_theme_accent_default
        ThemeAccentColor.Blue -> R.string.settings_theme_accent_blue
        ThemeAccentColor.Purple -> R.string.settings_theme_accent_purple
        ThemeAccentColor.Pink -> R.string.settings_theme_accent_pink
        ThemeAccentColor.Red -> R.string.settings_theme_accent_red
        ThemeAccentColor.Orange -> R.string.settings_theme_accent_orange
        ThemeAccentColor.Yellow -> R.string.settings_theme_accent_yellow
        ThemeAccentColor.Green -> R.string.settings_theme_accent_green
        ThemeAccentColor.Teal -> R.string.settings_theme_accent_teal
    },
)

@Composable
fun ThemePaletteStyle.label(): String = stringResource(
    when (this) {
        ThemePaletteStyle.Neutral -> R.string.settings_theme_palette_neutral
        ThemePaletteStyle.Vibrant -> R.string.settings_theme_palette_vibrant
        ThemePaletteStyle.Expressive -> R.string.settings_theme_palette_expressive
        ThemePaletteStyle.Rainbow -> R.string.settings_theme_palette_rainbow
        ThemePaletteStyle.FruitSalad -> R.string.settings_theme_palette_fruit_salad
        ThemePaletteStyle.Monochrome -> R.string.settings_theme_palette_monochrome
        ThemePaletteStyle.Fidelity -> R.string.settings_theme_palette_fidelity
        ThemePaletteStyle.Content -> R.string.settings_theme_palette_content
        else -> R.string.settings_theme_palette_tonal_spot
    },
)


@Composable
fun FloatingBottomBarStyle.label(): String = stringResource(
    when (this) {
        FloatingBottomBarStyle.Glass -> R.string.settings_theme_floating_bottom_bar_style_glass
        FloatingBottomBarStyle.IosLike -> R.string.settings_theme_floating_bottom_bar_style_ios_like
    },
)

@Composable
fun PagerInterceptionMode.label(): String = stringResource(
    when (this) {
        PagerInterceptionMode.Native -> R.string.settings_theme_pager_gesture_native
        PagerInterceptionMode.CrossAxis -> R.string.settings_theme_pager_gesture_cross_axis
    },
)

@Composable
fun TopBarBlurStyle.label(): String = stringResource(
    when (this) {
        TopBarBlurStyle.Gaussian -> R.string.settings_theme_blur_style_gaussian
        TopBarBlurStyle.Progressive -> R.string.settings_theme_blur_style_progressive
    },
)

@Composable
fun TopBarBlurStyle.summary(): String = stringResource(
    when (this) {
        TopBarBlurStyle.Gaussian -> R.string.settings_theme_blur_style_gaussian_summary
        TopBarBlurStyle.Progressive -> R.string.settings_theme_blur_style_progressive_summary
    },
)
