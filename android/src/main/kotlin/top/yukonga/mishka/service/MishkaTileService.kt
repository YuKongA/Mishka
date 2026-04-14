package top.yukonga.mishka.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyState

class MishkaTileService : TileService() {

    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        stateJob = CoroutineScope(Dispatchers.Main).launch {
            ProxyServiceBridge.state.collect { status ->
                val tile = qsTile ?: return@collect
                tile.state = when (status.state) {
                    ProxyState.Running -> Tile.STATE_ACTIVE
                    ProxyState.Starting -> Tile.STATE_ACTIVE
                    else -> Tile.STATE_INACTIVE
                }
                tile.subtitle = when (status.state) {
                    ProxyState.Running -> "已连接"
                    ProxyState.Starting -> "连接中..."
                    ProxyState.Error -> "错误"
                    else -> "未连接"
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

    override fun onClick() {
        super.onClick()
        val currentState = ProxyServiceBridge.state.value.state
        // 启动中时忽略点击，防止重复操作
        if (currentState == ProxyState.Starting) return

        if (currentState == ProxyState.Running) {
            MishkaTunService.stop(applicationContext)
        } else {
            MishkaTunService.start(applicationContext)
        }
    }
}
