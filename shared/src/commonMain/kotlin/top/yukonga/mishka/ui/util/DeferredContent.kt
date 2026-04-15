package top.yukonga.mishka.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.navigation3.ui.LocalNavAnimatedContentScope

/**
 * 导航动画完成后才返回 true，用于延迟组合重内容。
 *
 * 时间线：
 * - 动画进行中：返回 false → 页面显示轻量占位符（动画流畅）
 * - 动画结束 + 1 帧：返回 true → 开始组合重内容
 *   （卡顿不可见，因为页面已经静止）
 *
 * 粘性状态：一旦 true 不再变回 false，退出动画时内容保持可见。
 */
@Composable
fun rememberContentReady(): Boolean {
    val scope = LocalNavAnimatedContentScope.current
    val transitionRunning = scope.transition.isRunning
    val ready = remember { mutableStateOf(false) }

    LaunchedEffect(transitionRunning) {
        if (!transitionRunning && !ready.value) {
            withFrameNanos { }
            ready.value = true
        }
    }

    return ready.value
}
