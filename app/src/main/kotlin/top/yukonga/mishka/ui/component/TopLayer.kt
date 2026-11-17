package top.yukonga.mishka.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * 在应用根层之上的全屏覆盖层，供悬浮玻璃菜单等需要跨过侧栏或底栏显示的弹窗提升渲染层级。
 */
@Stable
class TopLayerState {
    internal val entries = mutableStateListOf<TopLayerEntry>()
    private var nextId = 0

    internal fun allocateId(): Int = nextId++
}

internal class TopLayerEntry(
    val id: Int,
    content: @Composable BoxScope.() -> Unit,
) {
    var content by androidx.compose.runtime.mutableStateOf(content)
}

val LocalTopLayer = staticCompositionLocalOf<TopLayerState?> { null }

/**
 * 将 [content] 提升到 [TopLayerHost] 渲染；若当前上下文未提供 [LocalTopLayer]，则原地渲染。
 */
@Composable
fun TopLayer(content: @Composable BoxScope.() -> Unit) {
    val layer = LocalTopLayer.current
    if (layer == null) {
        Box(modifier = Modifier.fillMaxSize(), content = content)
        return
    }
    val entry = remember(layer) { TopLayerEntry(layer.allocateId(), content) }
    entry.content = content
    DisposableEffect(layer, entry) {
        layer.entries.add(entry)
        onDispose { layer.entries.remove(entry) }
    }
}

/**
 * 渲染所有通过 [TopLayer] 提升的覆盖层；应放置在导航外壳之后以覆盖其上。
 */
@Composable
fun TopLayerHost(
    state: TopLayerState,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        state.entries.forEach { entry ->
            key(entry.id) { entry.content(this) }
        }
    }
}
