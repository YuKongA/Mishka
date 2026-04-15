package top.yukonga.mishka.platform

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.mishka.util.AppIconCache
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.miuixShape

@Composable
actual fun AppIcon(
    packageName: String,
    modifier: Modifier,
    size: Dp,
) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }

    val cached = remember(packageName) { AppIconCache.getFromCache(packageName) }
    var bitmap by remember(packageName) { mutableStateOf(cached) }

    if (cached == null) {
        LaunchedEffect(packageName) {
            try {
                bitmap = AppIconCache.loadIcon(context, packageName, sizePx)
            } catch (_: Exception) {
                // 加载失败，保持 null
            }
        }
    }

    Box(modifier = modifier.size(size)) {
        if (cached != null) {
            // 缓存命中，直接显示
            Image(
                bitmap = cached.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(size),
            )
        } else {
            Crossfade(
                targetState = bitmap,
                animationSpec = tween(durationMillis = 150),
                label = "AppIconFade",
            ) { icon ->
                if (icon != null) {
                    Image(
                        bitmap = icon.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(size),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(size)
                            .clip(miuixShape(8.dp))
                            .background(MiuixTheme.colorScheme.secondaryContainer),
                    )
                }
            }
        }
    }
}
