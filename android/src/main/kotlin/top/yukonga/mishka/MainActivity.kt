package top.yukonga.mishka

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanCustomCode
import io.github.g00fy2.quickie.config.BarcodeFormat
import io.github.g00fy2.quickie.config.ScannerConfig
import kotlinx.coroutines.launch
import top.yukonga.mishka.data.database.getAppDatabase
import top.yukonga.mishka.data.repository.OverrideJsonStore
import top.yukonga.mishka.data.repository.ProfileProcessor
import top.yukonga.mishka.data.repository.SubscriptionProxyResolver
import top.yukonga.mishka.data.repository.SubscriptionRepository
import top.yukonga.mishka.platform.AppListProvider
import top.yukonga.mishka.platform.AndroidWifiPolicy
import top.yukonga.mishka.platform.BootStartManager
import top.yukonga.mishka.platform.FilePicker
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.WifiPolicyController
import top.yukonga.mishka.service.AndroidProfileFileManager
import top.yukonga.mishka.service.RootHelper
import top.yukonga.mishka.viewmodel.AppProxyViewModel
import top.yukonga.mishka.viewmodel.ConnectionViewModel
import top.yukonga.mishka.viewmodel.DnsQueryViewModel
import top.yukonga.mishka.viewmodel.ExternalControlViewModel
import top.yukonga.mishka.viewmodel.HomeViewModel
import top.yukonga.mishka.viewmodel.LogViewModel
import top.yukonga.mishka.viewmodel.MetaSettingsViewModel
import top.yukonga.mishka.viewmodel.NetworkSettingsViewModel
import top.yukonga.mishka.viewmodel.ProviderViewModel
import top.yukonga.mishka.viewmodel.ProxyViewModel
import top.yukonga.mishka.viewmodel.SubscriptionViewModel

class MainActivity : ComponentActivity() {

    private lateinit var serviceController: ProxyServiceController
    private lateinit var homeViewModel: HomeViewModel
    private lateinit var subscriptionViewModel: SubscriptionViewModel
    private lateinit var proxyViewModel: ProxyViewModel
    private lateinit var logViewModel: LogViewModel
    private lateinit var providerViewModel: ProviderViewModel
    private lateinit var connectionViewModel: ConnectionViewModel
    private lateinit var dnsQueryViewModel: DnsQueryViewModel
    private lateinit var networkSettingsViewModel: NetworkSettingsViewModel
    private lateinit var metaSettingsViewModel: MetaSettingsViewModel
    private lateinit var externalControlViewModel: ExternalControlViewModel
    private lateinit var appProxyViewModel: AppProxyViewModel
    private lateinit var filePicker: FilePicker
    private lateinit var scanQrLauncher: ActivityResultLauncher<ScannerConfig>
    private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>
    private lateinit var wifiPermissionLauncher: ActivityResultLauncher<Array<String>>
    private var qrResultCallback: ((String?) -> Unit)? = null
    private var wifiPermissionCallback: ((Boolean) -> Unit)? = null
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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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

        val storage = PlatformStorage(this)
        val database = getAppDatabase(this)
        val fileManager = AndroidProfileFileManager(this)
        // 清理 processing/ 残留：经 ProfileProcessor 的进程级锁串行，避免擦掉后台 ProfileWorker
        // 正在进行的更新写到 processing/ 的内容
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            ProfileProcessor.cleanupResidual(fileManager)
        }
        top.yukonga.mishka.platform.IconDiskCache.init(this)
        serviceController = ProxyServiceController(this)
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
        val wifiPolicyController = WifiPolicyController(this)
        logViewModel = LogViewModel()
        providerViewModel = ProviderViewModel()
        connectionViewModel = ConnectionViewModel()
        dnsQueryViewModel = DnsQueryViewModel()
        val overrideStore = OverrideJsonStore(fileManager)
        // 代理组图标下载走 mihomo mixed-port（Mishka 自身绕过 TUN，直连境外图标 CDN 极慢）；
        // 不受订阅走代理开关约束，代理运行中即生效
        val iconProxyResolver = SubscriptionProxyResolver(storage, overrideStore)
        top.yukonga.mishka.platform.IconLoader.setProxyResolver {
            iconProxyResolver.resolve(requireUserToggle = false)
        }
        networkSettingsViewModel = NetworkSettingsViewModel(overrideStore)
        metaSettingsViewModel = MetaSettingsViewModel(overrideStore)
        externalControlViewModel = ExternalControlViewModel(overrideStore)
        appProxyViewModel = AppProxyViewModel(
            storage = storage,
            appListProvider = AppListProvider(this),
            serviceController = serviceController,
        )

        // 单例 SubscriptionRepository：HomeViewModel 直接订阅 activeSubscription Flow，
        // SubscriptionViewModel 共享同一实例避免双流不一致（如订阅切换后主页流量栏不刷新）
        val subscriptionRepository = SubscriptionRepository(
            importedDao = database.importedDao(),
            pendingDao = database.pendingDao(),
            selectionDao = database.selectionDao(),
            storage = storage,
            fileManager = fileManager,
            scope = lifecycleScope,
        )

        subscriptionViewModel = SubscriptionViewModel(
            repository = subscriptionRepository,
            storage = storage,
            fileManager = fileManager,
        )

        proxyViewModel = ProxyViewModel(
            selectionDao = database.selectionDao(),
            getActiveUuid = { subscriptionRepository.getActive()?.id },
            storage = storage,
        )

        homeViewModel = HomeViewModel(
            serviceController = serviceController,
            overrideStore = overrideStore,
            connectionManager = MishkaApplication.instance.connectionManager,
            getActiveSubscriptionId = { subscriptionRepository.getActive()?.id },
            activeSubscription = subscriptionRepository.activeSubscription,
            onLiveProviderInfo = subscriptionRepository::setLiveProviderInfo,
        )

        // 监听共享 connectionManager 的 repository：mihomo 重启时单点 close 旧 + new 新
        // 这里只负责把当前 repo 分发给消费方 ViewModel，不持有 close 责任（manager 拥有）
        lifecycleScope.launch {
            MishkaApplication.instance.connectionManager.repository.collect { repo ->
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

        val initialColorMode = when (storage.getString(StorageKeys.DARK_MODE, "system")) {
            "light" -> 1
            "dark" -> 2
            else -> 0
        }

        // 隐藏后台卡片：通过 excludeFromRecents 从最近任务中移除
        if (storage.getString(StorageKeys.HIDE_TASK_CARD, "false") == "true") {
            setExcludeFromRecents(true)
        }

        setContent {
            var colorMode by remember { mutableIntStateOf(initialColorMode) }
            App(
                colorMode = colorMode,
                onColorModeChange = { colorMode = it },
                homeViewModel = homeViewModel,
                subscriptionViewModel = subscriptionViewModel,
                proxyViewModel = proxyViewModel,
                logViewModel = logViewModel,
                providerViewModel = providerViewModel,
                connectionViewModel = connectionViewModel,
                dnsQueryViewModel = dnsQueryViewModel,
                networkSettingsViewModel = networkSettingsViewModel,
                metaSettingsViewModel = metaSettingsViewModel,
                externalControlViewModel = externalControlViewModel,
                appProxyViewModel = appProxyViewModel,
                filePicker = filePicker,
                storage = storage,
                bootStartManager = BootStartManager(this@MainActivity),
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
                        recreate()
                    }
                } else null,
                onHideTaskCardChange = { enabled ->
                    setExcludeFromRecents(enabled)
                },
            )
        }
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
        serviceController.verifyAndSyncState()
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
                // TaskInfo.taskId 是 API 29 才加入的字段；26-28 上回退到 RecentTaskInfo.id
                @Suppress("DEPRECATION")
                val id = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.taskId else info.id
                id == currentTaskId
            }
            ?.setExcludeFromRecents(exclude)
    }
}
