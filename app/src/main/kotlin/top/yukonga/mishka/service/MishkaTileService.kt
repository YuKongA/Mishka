package top.yukonga.mishka.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import top.yukonga.mishka.R
import top.yukonga.mishka.platform.ExternalProxyAction
import top.yukonga.mishka.platform.ExternalProxyOutcome
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.platform.ProxyState

class MishkaTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        // 系统重复绑定时会再进一次而没有配对的 onStopListening，不先取消就留下一条仍在
        // updateTile 的旧 collector
        stateJob?.cancel()
        stateJob = scope.launch {
            ProxyServiceBridge.state.collect { status ->
                val tile = qsTile ?: return@collect
                tile.state = when (status.state) {
                    ProxyState.Running -> Tile.STATE_ACTIVE
                    ProxyState.Starting -> Tile.STATE_ACTIVE
                    else -> Tile.STATE_INACTIVE
                }
                tile.subtitle = when (status.state) {
                    ProxyState.Running -> getString(R.string.tile_connected)
                    ProxyState.Starting -> getString(R.string.tile_connecting)
                    ProxyState.Error -> getString(R.string.tile_error)
                    else -> getString(R.string.tile_disconnected)
                }
                tile.updateTile()
            }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val controller = ProxyServiceController(applicationContext)
        // 「该做什么」与 Automation intent 共用同一个决策（过渡态忽略、订阅校验、授权判定都在里面），
        // 这里只处理 Tile 特有的那一支：VPN 授权对话框要求 Activity 上下文，Tile 不是，需透明跳板
        when (val outcome = controller.applyExternalAction(ExternalProxyAction.Toggle)) {
            is ExternalProxyOutcome.NeedVpnPermission ->
                launchVpnPermissionActivity(outcome.subscriptionId)

            else -> Unit
        }
    }

    private fun launchVpnPermissionActivity(subscriptionId: String) {
        val intent = Intent(this, VpnPermissionActivity::class.java).apply {
            putExtra(VpnPermissionActivity.EXTRA_SUBSCRIPTION_ID, subscriptionId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
