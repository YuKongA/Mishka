package top.yukonga.mishka.ui.screen.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.mishka.platform.PlatformStorage

@Composable
fun AppearanceScreen(
    onBack: () -> Unit = {},
    storage: PlatformStorage? = null,
    onThemeChanged: (String) -> Unit = {},
    bottomPadding: Dp = 0.dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    var darkMode by remember {
        mutableStateOf(storage?.getString("dark_mode", "system") ?: "system")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "外观",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = bottomPadding,
            ),
        ) {
            item { SmallTitle(text = "主题") }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 6.dp),
                ) {
                    RadioButtonPreference(
                        title = "跟随系统",
                        selected = darkMode == "system",
                        onClick = {
                            darkMode = "system"
                            storage?.putString("dark_mode", "system")
                            onThemeChanged("system")
                        },
                    )
                    RadioButtonPreference(
                        title = "浅色模式",
                        selected = darkMode == "light",
                        onClick = {
                            darkMode = "light"
                            storage?.putString("dark_mode", "light")
                            onThemeChanged("light")
                        },
                    )
                    RadioButtonPreference(
                        title = "深色模式",
                        selected = darkMode == "dark",
                        onClick = {
                            darkMode = "dark"
                            storage?.putString("dark_mode", "dark")
                            onThemeChanged("dark")
                        },
                    )
                }
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}
