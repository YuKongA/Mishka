import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.mishka.App

fun main() = application {
    val controller = ThemeController()
    Window(
        onCloseRequest = ::exitApplication,
        title = "Mishka",
        state = rememberWindowState(width = 400.dp, height = 800.dp),
    ) {
        App(controller = controller)
    }
}
