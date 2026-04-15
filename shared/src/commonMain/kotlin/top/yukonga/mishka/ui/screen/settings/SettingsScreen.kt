package top.yukonga.mishka.ui.screen.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.mishka.platform.BootStartManager
import top.yukonga.mishka.platform.PlatformStorage

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    onNavigateNetworkSettings: () -> Unit = {},
    onNavigateMetaSettings: () -> Unit = {},
    onNavigateAppProxy: () -> Unit = {},
    onNavigateAbout: () -> Unit = {},
    bootStartManager: BootStartManager? = null,
    colorMode: Int = 0,
    onColorModeChange: (Int) -> Unit = {},
    storage: PlatformStorage? = null,
) {
    val scrollBehavior = MiuixScrollBehavior()
    var isAutoStartEnabled by remember {
        mutableStateOf(bootStartManager?.isEnabled() ?: false)
    }

    val themeItems = listOf("跟随系统", "浅色模式", "深色模式")

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = "设置",
                scrollBehavior = scrollBehavior,
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
            item {
                SmallTitle(text = "网络")
            }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 6.dp),
                ) {
                    ArrowPreference(
                        title = "覆写设置",
                        summary = "端口、DNS、网络选项覆写",
                        onClick = onNavigateNetworkSettings,
                    )
                    ArrowPreference(
                        title = "Meta 设置",
                        summary = "统一延迟、嗅探器等",
                        onClick = onNavigateMetaSettings,
                    )
                    ArrowPreference(
                        title = "分应用代理",
                        summary = "选择需要代理的应用",
                        onClick = onNavigateAppProxy,
                    )
                }
            }
            item {
                SmallTitle(text = "通用")
            }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 6.dp),
                ) {
                    if (bootStartManager != null) {
                        SwitchPreference(
                            title = "开机自启",
                            summary = "设备重启后自动启动代理",
                            checked = isAutoStartEnabled,
                            onCheckedChange = { checked ->
                                bootStartManager.setEnabled(checked)
                                storage?.putString("auto_start", if (checked) "true" else "false")
                                isAutoStartEnabled = checked
                            },
                        )
                    }
                    OverlayDropdownPreference(
                        title = "主题模式",
                        summary = themeItems.getOrElse(colorMode) { "跟随系统" },
                        items = themeItems,
                        selectedIndex = colorMode,
                        onSelectedIndexChange = { index ->
                            onColorModeChange(index)
                            val value = when (index) {
                                1 -> "light"
                                2 -> "dark"
                                else -> "system"
                            }
                            storage?.putString("dark_mode", value)
                        },
                    )
                    ArrowPreference(
                        title = "关于",
                        summary = "Mishka v${misc.VersionInfo.VERSION_NAME}",
                        onClick = onNavigateAbout,
                    )
                }
            }
        }
    }
}
