package top.yukonga.mishka.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

@Composable
actual fun AppIcon(
    packageName: String,
    modifier: Modifier,
    size: Dp,
) {
    // Desktop 不支持显示应用图标
}
