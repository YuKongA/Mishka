package top.yukonga.mishka.platform

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

@Composable
actual fun AppIcon(
    packageName: String,
    modifier: Modifier,
    size: Dp,
) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            drawable.toBitmap(
                width = 96,
                height = 96,
            ).asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier.size(size),
        )
    }
}
