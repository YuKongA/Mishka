package top.yukonga.mishka

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import top.yukonga.mishka.platform.BootStartManager
import top.yukonga.mishka.platform.FilePicker
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.ui.navigation.AppNavigation
import top.yukonga.mishka.viewmodel.AppProxyViewModel
import top.yukonga.mishka.viewmodel.ConnectionViewModel
import top.yukonga.mishka.viewmodel.DnsQueryViewModel
import top.yukonga.mishka.viewmodel.HomeViewModel
import top.yukonga.mishka.viewmodel.LogViewModel
import top.yukonga.mishka.viewmodel.ProviderViewModel
import top.yukonga.mishka.viewmodel.ProxyViewModel
import top.yukonga.mishka.viewmodel.OverrideSettingsViewModel
import top.yukonga.mishka.viewmodel.SubscriptionViewModel
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun App(
    colorMode: Int = 0,
    onColorModeChange: (Int) -> Unit = {},
    homeViewModel: HomeViewModel? = null,
    subscriptionViewModel: SubscriptionViewModel? = null,
    proxyViewModel: ProxyViewModel? = null,
    logViewModel: LogViewModel? = null,
    providerViewModel: ProviderViewModel? = null,
    connectionViewModel: ConnectionViewModel? = null,
    dnsQueryViewModel: DnsQueryViewModel? = null,
    overrideSettingsViewModel: OverrideSettingsViewModel? = null,
    appProxyViewModel: AppProxyViewModel? = null,
    filePicker: FilePicker? = null,
    storage: PlatformStorage? = null,
    bootStartManager: BootStartManager? = null,
    mihomoVersion: String = "",
    onScanQR: ((callback: (String?) -> Unit) -> Unit)? = null,
    onPredictiveBackChange: ((Boolean) -> Unit)? = null,
    hasRootPermission: Boolean = false,
) {
    val controller = remember(colorMode) {
        when (colorMode) {
            1 -> ThemeController(ColorSchemeMode.Light)
            2 -> ThemeController(ColorSchemeMode.Dark)
            else -> ThemeController(ColorSchemeMode.System)
        }
    }

    MiuixTheme(
        controller = controller,
        smoothRounding = false,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MiuixTheme.colorScheme.onBackground,
        ) {
            AppNavigation(
                colorMode = colorMode,
                onColorModeChange = onColorModeChange,
                homeViewModel = homeViewModel,
                subscriptionViewModel = subscriptionViewModel,
                proxyViewModel = proxyViewModel,
                logViewModel = logViewModel,
                providerViewModel = providerViewModel,
                connectionViewModel = connectionViewModel,
                dnsQueryViewModel = dnsQueryViewModel,
                overrideSettingsViewModel = overrideSettingsViewModel,
                appProxyViewModel = appProxyViewModel,
                filePicker = filePicker,
                storage = storage,
                bootStartManager = bootStartManager,
                mihomoVersion = mihomoVersion,
                onScanQR = onScanQR,
                onPredictiveBackChange = onPredictiveBackChange,
                hasRootPermission = hasRootPermission,
            )
        }
    }
}
