package top.yukonga.mishka.ui.navigation3

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class Navigator(
    val backStack: MutableList<NavKey>,
) {
    private val resultBus = mutableMapOf<String, MutableSharedFlow<Any>>()

    fun push(key: NavKey) {
        backStack.add(key)
    }

    fun replace(key: NavKey) {
        if (backStack.isNotEmpty()) {
            backStack[backStack.lastIndex] = key
        } else {
            backStack.add(key)
        }
    }

    fun pop() {
        if (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
    }

    fun popUntil(predicate: (NavKey) -> Boolean) {
        while (backStack.size > 1 && !predicate(backStack.last())) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    fun navigateForResult(route: Route, requestKey: String) {
        ensureChannel(requestKey)
        push(route)
    }

    fun <T : Any> setResult(requestKey: String, value: T) {
        ensureChannel(requestKey).tryEmit(value)
        pop()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> observeResult(requestKey: String): SharedFlow<T> =
        ensureChannel(requestKey) as SharedFlow<T>

    @OptIn(ExperimentalCoroutinesApi::class)
    fun clearResult(requestKey: String) {
        ensureChannel(requestKey).resetReplayCache()
    }

    fun current() = backStack.lastOrNull()

    fun backStackSize() = backStack.size

    private fun ensureChannel(key: String): MutableSharedFlow<Any> =
        resultBus.getOrPut(key) { MutableSharedFlow(replay = 1, extraBufferCapacity = 0) }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("LocalNavigator not provided")
}
