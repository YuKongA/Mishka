package top.yukonga.mishka

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanCustomCode
import io.github.g00fy2.quickie.config.BarcodeFormat
import io.github.g00fy2.quickie.config.ScannerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import top.yukonga.mishka.data.api.MihomoConnectionManager
import top.yukonga.mishka.data.repository.ProfileProcessor
import top.yukonga.mishka.data.repository.SubscriptionProxyResolver
import top.yukonga.mishka.platform.AndroidWifiPolicy
import top.yukonga.mishka.platform.BootStartManager
import top.yukonga.mishka.platform.FilePicker
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.WifiPolicyController
import top.yukonga.mishka.service.RootHelper
import top.yukonga.mishka.ui.theme.ThemeConfig
import top.yukonga.mishka.ui.theme.readThemeConfig
import top.yukonga.mishka.ui.theme.resolveIsDark
import top.yukonga.mishka.viewmodel.AppProxyViewModel
import top.yukonga.mishka.viewmodel.ConnectionViewModel
import top.yukonga.mishka.viewmodel.DnsQueryViewModel
import top.yukonga.mishka.viewmodel.AutomationViewModel
import top.yukonga.mishka.viewmodel.ExternalControlViewModel
import top.yukonga.mishka.viewmodel.HomeViewModel
import top.yukonga.mishka.viewmodel.LogViewModel
import top.yukonga.mishka.viewmodel.MetaSettingsViewModel
import top.yukonga.mishka.viewmodel.NetworkSettingsViewModel
import top.yukonga.mishka.viewmodel.OverrideProfileViewModel
import top.yukonga.mishka.viewmodel.ProviderViewModel
import top.yukonga.mishka.viewmodel.ProxyViewModel
import top.yukonga.mishka.viewmodel.SubscriptionViewModel

private const val STATE_DEEPLINK_NONCE = "deeplink_nonce"

class MainActivity : ComponentActivity() {

    private lateinit var serviceController: ProxyServiceController
    private lateinit var homeViewModel: HomeViewModel
    private lateinit var subscriptionViewModel: SubscriptionViewModel
    private lateinit var overrideProfileViewModel: OverrideProfileViewModel
    private lateinit var proxyViewModel: ProxyViewModel
    private lateinit var logViewModel: LogViewModel
    private lateinit var providerViewModel: ProviderViewModel
    private lateinit var connectionViewModel: ConnectionViewModel
    private lateinit var dnsQueryViewModel: DnsQueryViewModel
    private lateinit var networkSettingsViewModel: NetworkSettingsViewModel
    private lateinit var metaSettingsViewModel: MetaSettingsViewModel
    private lateinit var externalControlViewModel: ExternalControlViewModel
    private lateinit var automationViewModel: AutomationViewModel
    private lateinit var appProxyViewModel: AppProxyViewModel
    private lateinit var filePicker: FilePicker
    private lateinit var scanQrLauncher: ActivityResultLauncher<ScannerConfig>
    private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>
    private lateinit var wifiPermissionLauncher: ActivityResultLauncher<Array<String>>
    private var qrResultCallback: ((String?) -> Unit)? = null
    private var wifiPermissionCallback: ((Boolean) -> Unit)? = null
    private var latestThemeConfig: ThemeConfig? = null
    private var contentReady = false

    // 深链导入请求：AppNavigation 消费后回调置空
    private val pendingDeepLinkImport = mutableStateOf<DeepLinkImportRequest?>(null)
    private var consumedDeepLinkNonce: String? = null
    private val scannerConfig: ScannerConfig by lazy {
        ScannerConfig.build {
            setBarcodeFormats(listOf(BarcodeFormat.FORMAT_QR_CODE))
            setOverlayStringRes(R.string.qr_scanner_overlay)
            setShowTorchToggle(true)
            setShowCloseButton(true)
            setKeepScreenOn(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须先于 super.onCreate，postSplashScreenTheme 靠它换回 Theme.Mishka
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { !contentReady }

        consumedDeepLinkNonce = savedInstanceState?.getString(STATE_DEEPLINK_NONCE)
        acceptDeepLinkImport(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
        }

        scanQrLauncher = registerForActivityResult(ScanCustomCode()) { result ->
            val url: String? = when (result) {
                is QRResult.QRSuccess -> {
                    val raw = result.content.rawValue
                    when {
                        raw == null -> {
                            showQrToast(R.string.qr_unsupported)
                            null
                        }

                        raw.startsWith("http://") || raw.startsWith("https://") -> raw
                        else -> {
                            showQrToast(R.string.qr_invalid_subscription)
                            null
                        }
                    }
                }

                is QRResult.QRMissingPermission -> {
                    showQrToast(R.string.qr_permission_denied)
                    null
                }

                is QRResult.QRError -> {
                    showQrToast(R.string.qr_scan_failed)
                    null
                }

                is QRResult.QRUserCanceled -> null
            }
            qrResultCallback?.invoke(url)
            qrResultCallback = null
        }

        // 对象图由 Koin 管理（MishkaApplication.startKoin）；此处组合根从容器取图。
        // 仅需 Activity 上下文的资源（FilePicker / VPN 授权 launcher）仍由 Activity 直接持有。
        val storage: PlatformStorage = get()
        val initialThemeConfig = readThemeConfig(storage)
        updateEdgeToEdge(initialThemeConfig)
        // 清理 processing/ 残留与孤儿订阅目录：经 ProfileProcessor 的进程级锁串行，
        // 避免擦掉后台 ProfileWorker 正在进行的更新写到 processing/ 的内容
        val profileProcessor: ProfileProcessor = get()
        lifecycleScope.launch { profileProcessor.cleanupResidual() }
        top.yukonga.mishka.ui.platform.IconDiskCache.init(this)
        serviceController = get()
        // VPN 授权走 Activity Result API：MainActivity 注册 launcher，由 ProxyServiceController 触发，
        // 授权通过后回调里重新 startProxy（此时已持权限，直接拉起 Service）
        vpnPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                homeViewModel.startProxy()
            }
        }
        wifiPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val granted = result.isNotEmpty() && result.values.all { it }
            wifiPermissionCallback?.invoke(granted)
            wifiPermissionCallback = null
        }
        serviceController.setVpnPermissionLauncher(vpnPermissionLauncher)
        filePicker = FilePicker(this)
        val wifiPolicyController: WifiPolicyController = get()
        // Wi-Fi 策略 reconcile：备份恢复/force-stop 后 pref 与组件位、服务状态脱节，按 pref 幂等拉起
        if (storage.getString(StorageKeys.WIFI_POLICY_ENABLED, "false") == "true" &&
            wifiPolicyController.hasRequiredPermission()
        ) {
            wifiPolicyController.startMonitor()
        }
        // 代理组图标下载走 mihomo mixed-port（Mishka 自身绕过 TUN，直连境外图标 CDN 极慢）；
        // 不受订阅走代理开关约束，代理运行中即生效
        val iconProxyResolver: SubscriptionProxyResolver = get()
        val appScope: CoroutineScope = get()
        top.yukonga.mishka.ui.platform.IconLoader.init(appScope) {
            iconProxyResolver.resolve(requireUserToggle = false)
        }

        logViewModel = get()
        providerViewModel = get()
        connectionViewModel = get()
        dnsQueryViewModel = get()
        networkSettingsViewModel = get()
        metaSettingsViewModel = get()
        externalControlViewModel = get()
        automationViewModel = get()
        appProxyViewModel = get()
        subscriptionViewModel = get()
        overrideProfileViewModel = get()
        proxyViewModel = get()
        homeViewModel = get()

        // 监听共享 connectionManager 的 repository：mihomo 重启时单点 close 旧 + new 新
        // 这里只负责把当前 repo 分发给消费方 ViewModel，不持有 close 责任（manager 拥有）
        val connectionManager: MihomoConnectionManager = get()
        lifecycleScope.launch {
            connectionManager.repository.collect { repo ->
                proxyViewModel.setRepository(repo)
                logViewModel.setRepository(repo)
                providerViewModel.setRepository(repo)
                connectionViewModel.setRepository(repo)
                dnsQueryViewModel.setRepository(repo)
            }
        }

        // 异步检测 root 权限：先用缓存值，检测完成后更新 State 触发 recompose
        val hasRootState = androidx.compose.runtime.mutableStateOf(
            storage.getString(StorageKeys.HAS_ROOT, "false") == "true"
        )
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val hasRoot = RootHelper.hasRootAccess()
            storage.putString(StorageKeys.HAS_ROOT, if (hasRoot) "true" else "false")
            // ROOT 不可用时自动回退到 VPN 模式，防止卡在错误状态
            if (!hasRoot) {
                val current = storage.getString(StorageKeys.TUN_MODE, "vpn")
                if (current == "root_tun" || current == "root_tproxy") {
                    storage.putString(StorageKeys.TUN_MODE, "vpn")
                }
            }
            hasRootState.value = hasRoot
        }

        if (storage.getString(StorageKeys.HIDE_TASK_CARD, "false") == "true") {
            setExcludeFromRecents(true)
        }

        setContent {
            var themeConfig by remember { mutableStateOf(initialThemeConfig) }
            SideEffect {
                updateEdgeToEdge(themeConfig)
            }
            App(
                themeConfig = themeConfig,
                onThemeConfigChange = { themeConfig = it },
                homeViewModel = homeViewModel,
                subscriptionViewModel = subscriptionViewModel,
                overrideProfileViewModel = overrideProfileViewModel,
                proxyViewModel = proxyViewModel,
                logViewModel = logViewModel,
                providerViewModel = providerViewModel,
                connectionViewModel = connectionViewModel,
                dnsQueryViewModel = dnsQueryViewModel,
                networkSettingsViewModel = networkSettingsViewModel,
                metaSettingsViewModel = metaSettingsViewModel,
                externalControlViewModel = externalControlViewModel,
                automationViewModel = automationViewModel,
                appProxyViewModel = appProxyViewModel,
                filePicker = filePicker,
                storage = storage,
                bootStartManager = get<BootStartManager>(),
                onScanQR = { callback ->
                    qrResultCallback = callback
                    scanQrLauncher.launch(scannerConfig)
                },
                wifiPolicyController = wifiPolicyController,
                onRequestWifiPermission = { callback ->
                    requestWifiPolicyPermission(wifiPolicyController, callback)
                },
                hasRootPermission = hasRootState.value,
                onPredictiveBackChange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    { enabled ->
                        MishkaApplication.setEnableOnBackInvokedCallback(applicationInfo, enabled)
                        recreateWithoutTransition()
                    }
                } else null,
                onHideTaskCardChange = { enabled ->
                    setExcludeFromRecents(enabled)
                },
                deepLinkImport = pendingDeepLinkImport.value,
                onDeepLinkImportConsumed = { pendingDeepLinkImport.value = null },
                backupViewModel = get(),
                onRestartApp = { restartApplication() },
            )
            SideEffect { contentReady = true }
        }
    }

    // WebDAV 恢复后重启进程：OverrideJsonStore / Repository Flow 等内存态不随磁盘恢复刷新，
    // 冷启动重建是唯一可靠的加载路径
    private fun restartApplication() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptDeepLinkImport(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        consumedDeepLinkNonce?.let { outState.putString(STATE_DEEPLINK_NONCE, it) }
    }

    // nonce 去重：进程死亡恢复会经 onCreate 重放旧深链 intent（需跳过），而「任务存活但进程
    // 被杀」时新深链可能带着恢复态送达（需接受），savedInstanceState 判空无法区分这两种情况
    private fun acceptDeepLinkImport(intent: Intent?) {
        if (intent?.action != ExternalImportActivity.ACTION_IMPORT_SUBSCRIPTION) return
        val nonce = intent.getStringExtra(ExternalImportActivity.EXTRA_IMPORT_NONCE) ?: return
        if (nonce == consumedDeepLinkNonce) return
        val url = intent.getStringExtra(ExternalImportActivity.EXTRA_IMPORT_URL)
        if (url.isNullOrBlank()) return
        consumedDeepLinkNonce = nonce
        pendingDeepLinkImport.value = DeepLinkImportRequest(
            url = url,
            name = intent.getStringExtra(ExternalImportActivity.EXTRA_IMPORT_NAME).orEmpty(),
            intervalMinutes = intent.getLongExtra(ExternalImportActivity.EXTRA_IMPORT_INTERVAL_MINUTES, 0L),
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) latestThemeConfig?.let(::updateEdgeToEdge)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // manifest configChanges=uiMode 吞掉了深浅色切换的重建，前台期间系统定时深色模式
        // 翻转既无焦点变化也不触发 themeConfig SideEffect，只有这里能重新应用系统栏外观
        latestThemeConfig?.let(::updateEdgeToEdge)
    }

    private fun updateEdgeToEdge(themeConfig: ThemeConfig) {
        latestThemeConfig = themeConfig
        val isDark = themeConfig.resolveIsDark(
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
        )
        val systemBarStyle = themeConfig.systemBarStyle()
        enableEdgeToEdge(
            statusBarStyle = systemBarStyle,
            navigationBarStyle = systemBarStyle,
        )
        enforceSystemBarsAppearance(isDark)
        window.decorView.post {
            enforceSystemBarsAppearance(isDark)
        }
    }

    private fun ThemeConfig.systemBarStyle(): SystemBarStyle = when (colorMode) {
        1 -> SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        2 -> SystemBarStyle.dark(Color.TRANSPARENT)
        else -> SystemBarStyle.auto(
            lightScrim = Color.TRANSPARENT,
            darkScrim = Color.TRANSPARENT,
            detectDarkMode = { resources ->
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                        Configuration.UI_MODE_NIGHT_YES
            },
        )
    }

    private fun enforceSystemBarsAppearance(isDark: Boolean) {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }

    @Suppress("DEPRECATION")
    private fun recreateWithoutTransition() {
        overridePendingTransition(0, 0)
        recreate()
        overridePendingTransition(0, 0)
    }

    private fun requestWifiPolicyPermission(
        controller: WifiPolicyController,
        callback: (Boolean) -> Unit,
    ) {
        if (controller.hasRequiredPermission()) {
            callback(true)
            return
        }
        wifiPermissionCallback = callback
        wifiPermissionLauncher.launch(AndroidWifiPolicy.requiredPermissions())
    }

    override fun onResume() {
        super.onResume()
        latestThemeConfig?.let(::updateEdgeToEdge)
        serviceController.verifyAndSyncState()
    }

    // 主页的 /proc 采样与 /configs 轮询挂在 viewModelScope 上，不门控就会在后台一直跑
    override fun onStart() {
        super.onStart()
        homeViewModel.setUiVisible(true)
    }

    // controller 是 Koin single 随进程存活，不解绑就一直强引用已销毁的 Activity 与整棵
    // Compose 树；此后再调 requestVpnPermission 还会撞 unregistered launcher 异常
    override fun onDestroy() {
        serviceController.setVpnPermissionLauncher(null)
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        homeViewModel.setUiVisible(false)
    }

    private fun showQrToast(@StringRes resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun setExcludeFromRecents(exclude: Boolean) {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val currentTaskId = taskId
        am.appTasks
            .firstOrNull { task ->
                val info = task.taskInfo ?: return@firstOrNull false
                info.taskId == currentTaskId
            }
            ?.setExcludeFromRecents(exclude)
    }
}
