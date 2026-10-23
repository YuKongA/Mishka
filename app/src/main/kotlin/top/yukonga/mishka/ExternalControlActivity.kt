package top.yukonga.mishka

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import top.yukonga.mishka.platform.ExternalControlPolicy
import top.yukonga.mishka.platform.ExternalProxyAction
import top.yukonga.mishka.platform.ExternalProxyOutcome
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.service.VpnPermissionActivity

/**
 * 用有窗口的透明主题是必须的：Android 12 起后台应用不能启动前台服务，「应用有一个 Activity 被
 * 启动」正是拿到豁免的条件，换成 Theme.NoDisplay 会让「启动」静默失败。
 *
 * 空 taskAffinity 让本类不属于任何 task，NEW_TASK 启动时系统只能新建 task，主界面不会被前置——
 * 只照抄 Activity 声明本身实测挡不住。给出入都取消转场，避免把一次自动化请求放大成可见的切换。
 */
class ExternalControlActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 静默拒绝：不弹提示，避免被调用方轮询时反复打扰；调用方靠 CANCELED 自知失败
        if (!ExternalControlPolicy.isAllowed(this)) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        overrideTransition()
        when (intent?.action) {
            ACTION_START_PROXY -> controlProxy(ExternalProxyAction.Start)
            ACTION_STOP_PROXY -> controlProxy(ExternalProxyAction.Stop)
            ACTION_TOGGLE_PROXY -> controlProxy(ExternalProxyAction.Toggle)
            ACTION_RESTART_PROXY -> controlProxy(ExternalProxyAction.Restart)
            else -> finish()
        }
    }

    override fun finish() {
        super.finish()
        overrideTransition()
    }

    /** 出入都不放转场：一次自动化请求不该被放大成看得见的切换。 */
    private fun overrideTransition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    /**
     * 启停一律经 [ProxyServiceController]：本 Activity 不碰 Service，也不自己判「该不该启动」——
     * 直拉 Service 会绕过 active 订阅与 config.yaml 落盘的校验。
     */
    private fun controlProxy(action: ExternalProxyAction) {
        val outcome = ProxyServiceController(this).applyExternalAction(action)
        when (outcome) {
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

            // 授权对话框本身由 VpnPermissionActivity 调 VpnService.prepare() 弹，这里只是能直接
            // startActivity 起这个跳板；Tile 是 Service，起它得走 startActivityAndCollapse
            is ExternalProxyOutcome.NeedVpnPermission -> startActivity(
                Intent(this, VpnPermissionActivity::class.java)
                    .putExtra(VpnPermissionActivity.EXTRA_SUBSCRIPTION_ID, outcome.subscriptionId)
            )
        }
        setResult(if (outcome.satisfiedRequest()) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    /**
     * AlreadyRunning / NotRunning 算达成（呼叫方要的状态本来就在那儿），Busy 与无订阅算未达成
     * —— Tasker 这类调用方要据此决定重试还是报警。
     */
    private fun ExternalProxyOutcome.satisfiedRequest(): Boolean = when (this) {
        ExternalProxyOutcome.Started,
        ExternalProxyOutcome.Stopped,
        ExternalProxyOutcome.Restarted,
        ExternalProxyOutcome.AlreadyRunning,
        ExternalProxyOutcome.NotRunning -> true

        ExternalProxyOutcome.Busy,
        ExternalProxyOutcome.NoSubscription,
        is ExternalProxyOutcome.NeedVpnPermission -> false
    }

    companion object {
        // 这四个字面量在 Manifest 的 intent-filter 里另有一份，改名必须两处同改 —— 编译器不会替你
        // 对齐，只改一处会让外部 intent 匹配不到，表现成「点了没反应」
        const val ACTION_START_PROXY = "top.yukonga.mishka.action.START_PROXY"
        const val ACTION_STOP_PROXY = "top.yukonga.mishka.action.STOP_PROXY"
        const val ACTION_TOGGLE_PROXY = "top.yukonga.mishka.action.TOGGLE_PROXY"
        const val ACTION_RESTART_PROXY = "top.yukonga.mishka.action.RESTART_PROXY"
    }
}
