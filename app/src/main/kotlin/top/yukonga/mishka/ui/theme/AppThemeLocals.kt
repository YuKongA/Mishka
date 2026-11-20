package top.yukonga.mishka.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize

val LocalAppDarkMode = staticCompositionLocalOf { false }
val LocalAppMonetEnabled = staticCompositionLocalOf { false }
val LocalPlatformDensity = staticCompositionLocalOf<Density?> { null }
val LocalContainerSize = staticCompositionLocalOf<IntSize?> { null }
