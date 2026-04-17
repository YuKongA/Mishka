package top.yukonga.mishka.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.mishka.R
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyState
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.TunMode

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

    override fun onClick() {
        super.onClick()
        val currentState = ProxyServiceBridge.state.value
        // 启动中时忽略点击，防止重复操作
        if (currentState.state == ProxyState.Starting) return

        if (currentState.state == ProxyState.Running) {
            // 停止时根据当前运行模式路由
            when (currentState.tunMode) {
                TunMode.Root -> MishkaRootService.stop(applicationContext)
                TunMode.Vpn -> MishkaTunService.stop(applicationContext)
            }
        } else {
            // 启动时读取设置中的模式
            val isRoot = PlatformStorage(applicationContext).getString(StorageKeys.TUN_MODE, "vpn") == "root"
            if (isRoot) {
                MishkaRootService.start(applicationContext)
            } else {
                MishkaTunService.start(applicationContext)
            }
        }
    }
}
