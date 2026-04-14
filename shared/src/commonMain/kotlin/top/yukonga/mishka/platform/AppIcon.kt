package top.yukonga.mishka.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
expect fun AppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
)
