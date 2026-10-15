package top.yukonga.mishka

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import top.yukonga.mishka.platform.ExternalProxyAction
import top.yukonga.mishka.platform.ExternalProxyOutcome
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.service.VpnPermissionActivity
import java.util.UUID

/**
 * 外部入口跳板：Automation 的代理启停（显式 intent）与 clash:// 订阅导入深链都从这里进，做法照 CMFA。
 * 用有窗口的透明主题是必须的：Android 12 起后台应用不能启动前台服务，「应用有一个 Activity 被
 * 启动」正是拿到豁免的条件，换成 Theme.NoDisplay 会让「启动」静默失败。
 * 不唤醒主界面是 MainActivity 那边 singleTask 的效果，不靠这里做隔离；给出入都取消转场，避免把
 * 一次自动化请求放大成可见的界面切换。
 * 浏览器把本 Activity 启进自己的 task，导入必须 NEW_TASK 路由回 Mishka 自身 task；
 * SINGLE_TOP + CLEAR_TOP 让已存活的 MainActivity 走 onNewIntent 而非销毁重建。
 * nonce 供 MainActivity 去重：进程死亡恢复会重放旧 intent，仅凭 savedInstanceState 无法
 * 与「恢复态下送达的新深链」区分。
 */
class ExternalControlActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        when (intent?.action) {
            ACTION_START_PROXY -> controlProxy(ExternalProxyAction.Start)
            ACTION_STOP_PROXY -> controlProxy(ExternalProxyAction.Stop)
            ACTION_TOGGLE_PROXY -> controlProxy(ExternalProxyAction.Toggle)
            ACTION_RESTART_PROXY -> controlProxy(ExternalProxyAction.Restart)
            else -> importSubscription()
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /**
     * 启停一律经 [ProxyServiceController]，与磁贴共用同一份状态判定与订阅校验 —— 本 Activity 不碰
     * Service，也不自己判「该不该启动」。
     */
    private fun controlProxy(action: ExternalProxyAction) {
        when (val outcome = ProxyServiceController(this).applyExternalAction(action)) {
            ExternalProxyOutcome.Started -> showToast(getString(R.string.external_proxy_started))
            ExternalProxyOutcome.Stopped -> showToast(getString(R.string.external_proxy_stopped))
            ExternalProxyOutcome.Restarted -> showToast(getString(R.string.external_proxy_restarted))
            ExternalProxyOutcome.AlreadyRunning ->
                showToast(getString(R.string.external_proxy_already_running))

            ExternalProxyOutcome.NotRunning ->
                showToast(getString(R.string.external_proxy_not_running))

            // 过渡态（Starting / Stopping）不播报：用户刚点过或刚被自动化触发过，再弹一条是噪音
            ExternalProxyOutcome.Busy -> Unit

            // 无可用订阅已由 resolveStartSubscriptionId 播报并置 Error，不重复提示
            ExternalProxyOutcome.NoSubscription -> Unit

            // 本 Activity 就是 Activity 上下文，授权对话框可直接拉（Tile 那条路径拉不了）
            is ExternalProxyOutcome.NeedVpnPermission -> startActivity(
                Intent(this, VpnPermissionActivity::class.java)
                    .putExtra(VpnPermissionActivity.EXTRA_SUBSCRIPTION_ID, outcome.subscriptionId)
            )
        }
        finish()
    }

    private fun importSubscription() {
        // 本 Activity 是 exported：显式 intent 可塞非层级 URI（clash:install-config），
        // 其上调 getQueryParameter 会抛 UnsupportedOperationException
        val uri = intent?.data?.takeIf { it.isHierarchical }
        val url = uri?.getQueryParameter(QUERY_URL)?.trim()
        if (url.isNullOrEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            showToast(getString(R.string.deep_link_invalid))
            finish()
            return
        }
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_IMPORT_SUBSCRIPTION
                putExtra(EXTRA_IMPORT_URL, url)
                putExtra(EXTRA_IMPORT_NAME, uri.getQueryParameter(QUERY_NAME)?.trim().orEmpty())
                putExtra(
                    EXTRA_IMPORT_INTERVAL_MINUTES,
                    uri.getQueryParameter(QUERY_UPDATE_INTERVAL)?.toLongOrNull()?.coerceAtLeast(0) ?: 0L,
                )
                putExtra(EXTRA_IMPORT_NONCE, UUID.randomUUID().toString())
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
        )
        finish()
    }

    companion object {
        const val ACTION_IMPORT_SUBSCRIPTION = "top.yukonga.mishka.action.IMPORT_SUBSCRIPTION"

        /** Automation 入口：外部按「类名 + 下列 action」发显式 intent，用法见 README「自动化」。 */
        const val ACTION_START_PROXY = "top.yukonga.mishka.action.START_PROXY"
        const val ACTION_STOP_PROXY = "top.yukonga.mishka.action.STOP_PROXY"
        const val ACTION_TOGGLE_PROXY = "top.yukonga.mishka.action.TOGGLE_PROXY"
        const val ACTION_RESTART_PROXY = "top.yukonga.mishka.action.RESTART_PROXY"

        const val EXTRA_IMPORT_URL = "import_url"
        const val EXTRA_IMPORT_NAME = "import_name"
        const val EXTRA_IMPORT_INTERVAL_MINUTES = "import_interval_minutes"
        const val EXTRA_IMPORT_NONCE = "import_nonce"

        private const val QUERY_URL = "url"
        private const val QUERY_NAME = "name"
        private const val QUERY_UPDATE_INTERVAL = "update-interval"
    }
}

/** 深链导入请求：MainActivity 解析 intent 后经 App/AppNavigation 透传，驱动导航到预填的添加订阅页 */
data class DeepLinkImportRequest(
    val url: String,
    val name: String,
    val intervalMinutes: Long,
)
