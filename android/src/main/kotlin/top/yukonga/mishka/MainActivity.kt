package top.yukonga.mishka

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import top.yukonga.mishka.data.api.MihomoApiClient
import top.yukonga.mishka.data.api.MihomoWebSocket
import top.yukonga.mishka.data.database.DataMigration
import top.yukonga.mishka.data.database.getAppDatabase
import top.yukonga.mishka.data.repository.MihomoRepository
import top.yukonga.mishka.platform.FilePicker
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.platform.ProxyState
import top.yukonga.mishka.service.AndroidProfileFileManager
import top.yukonga.mishka.service.ProfileFileOps
import top.yukonga.mishka.platform.AppListProvider
import top.yukonga.mishka.platform.BootStartManager
import top.yukonga.mishka.viewmodel.AppProxyViewModel
import top.yukonga.mishka.viewmodel.ConnectionViewModel
import top.yukonga.mishka.viewmodel.DnsQueryViewModel
import top.yukonga.mishka.viewmodel.HomeViewModel
import top.yukonga.mishka.viewmodel.OverrideSettingsViewModel
import top.yukonga.mishka.viewmodel.LogViewModel
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
    private lateinit var overrideSettingsViewModel: OverrideSettingsViewModel
    private lateinit var appProxyViewModel: AppProxyViewModel
    private lateinit var filePicker: FilePicker
    private lateinit var scanQrLauncher: ActivityResultLauncher<Nothing?>
    private var qrResultCallback: ((String?) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        scanQrLauncher = registerForActivityResult(ScanQRCode()) { result ->
            val url = when (result) {
                is QRResult.QRSuccess -> result.content.rawValue
                else -> null
            }
            qrResultCallback?.invoke(url)
            qrResultCallback = null
        }

        val storage = PlatformStorage(this)
        val database = getAppDatabase(this)
        DataMigration.migrateIfNeeded(storage, database)
        ProfileFileOps.migrateProfileDirs(this)
        top.yukonga.mishka.platform.IconDiskCache.init(this)
        serviceController = ProxyServiceController(this)
        filePicker = FilePicker(this)
        logViewModel = LogViewModel()
        providerViewModel = ProviderViewModel()
        connectionViewModel = ConnectionViewModel()
        dnsQueryViewModel = DnsQueryViewModel()
        overrideSettingsViewModel = OverrideSettingsViewModel(storage = storage)
        appProxyViewModel = AppProxyViewModel(
            storage = storage,
            appListProvider = AppListProvider(this),
        )

        val fileManager = AndroidProfileFileManager(this)
        subscriptionViewModel = SubscriptionViewModel(
            database = database,
            storage = storage,
            fileManager = fileManager,
        )

        proxyViewModel = ProxyViewModel(
            selectionDao = database.selectionDao(),
            getActiveUuid = { subscriptionViewModel.getActiveSubscription()?.id },
        )

        homeViewModel = HomeViewModel(
            serviceController = serviceController,
            getActiveSubscriptionId = { subscriptionViewModel.getActiveSubscription()?.id },
        )

        // 监听代理状态，连接/断开 ProxyViewModel 和 LogViewModel
        lifecycleScope.launch {
            ProxyServiceBridge.state.collect { status ->
                when (status.state) {
                    ProxyState.Running -> {
                        val client = MihomoApiClient(secret = status.secret)
                        val ws = MihomoWebSocket(client)
                        val repo = MihomoRepository(client, ws)
                        proxyViewModel.setRepository(repo)
                        logViewModel.setRepository(repo)
                        providerViewModel.setRepository(repo)
                        connectionViewModel.setRepository(repo)
                        dnsQueryViewModel.setRepository(repo)
                        overrideSettingsViewModel.setRepository(repo)
                    }
                    else -> {
                        proxyViewModel.setRepository(null)
                        logViewModel.setRepository(null)
                        providerViewModel.setRepository(null)
                        connectionViewModel.setRepository(null)
                        dnsQueryViewModel.setRepository(null)
                        overrideSettingsViewModel.setRepository(null)
                    }
                }
            }
        }

        val initialColorMode = when (storage.getString("dark_mode", "system")) {
            "light" -> 1
            "dark" -> 2
            else -> 0
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
                overrideSettingsViewModel = overrideSettingsViewModel,
                appProxyViewModel = appProxyViewModel,
                filePicker = filePicker,
                storage = storage,
                bootStartManager = BootStartManager(this@MainActivity),
                onScanQR = { callback ->
                    qrResultCallback = callback
                    scanQrLauncher.launch(null)
                },
            )
        }
    }

    @Deprecated("Use ActivityResult API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            ProxyServiceController.VPN_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    homeViewModel.startProxy()
                }
            }
            FilePicker.FILE_PICK_REQUEST_CODE -> {
                filePicker.handleResult(requestCode, resultCode, data)
            }
        }
    }
}
