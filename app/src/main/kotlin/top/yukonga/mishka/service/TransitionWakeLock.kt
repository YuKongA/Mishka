package top.yukonga.mishka.service

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock

/**
 * 代理启停与后台订阅更新的短时 [PowerManager.PARTIAL_WAKE_LOCK] 工具。
 *
 * 所有锁均非引用计数且强制带超时上限，异常吞掉避免影响主流程；用于防止闹钟唤醒后的
 * 后台更新 → Service 重启交接窗口内 SoC 休眠把重启链挂起在过渡态。
 */
internal object TransitionWakeLock {

    const val HANDOFF_TIMEOUT_MS = 15_000L
    const val PROFILE_UPDATE_TIMEOUT_MS = 180_000L
    const val SERVICE_COMMAND_TIMEOUT_MS = 90_000L
    const val SERVICE_START_TIMEOUT_MS = 150_000L

    private const val TAG_HANDOFF = "mishka:service-handoff"
    private const val EXTRA_HANDOFF_TOKEN = "mishka_wake_handoff_token"

    private val handoffGuard = Any()
    private var handoffLock: PowerManager.WakeLock? = null
    private var nextHandoffToken = 0L

    // 记录尚未到达 onStartCommand 的交接 token 及其到期单调时间：
    // 跨 Service 乱序到达不提前释放、未携带 token 的 ACTION_STOP/重投不误放、意外丢失的 Intent 到期自动回收。
    private val pendingHandoffs = HashMap<Long, Long>()

    fun acquire(context: Context, tag: String, timeoutMs: Long): PowerManager.WakeLock? =
        runCatching {
            context.applicationContext
                .getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
                ?.apply {
                    setReferenceCounted(false)
                    acquire(timeoutMs)
                }
        }.getOrNull()

    fun PowerManager.WakeLock?.releaseQuietly() {
        if (this == null) return
        runCatching {
            if (isHeld) release()
        }
    }

    /**
     * 兜住 `startService` / `startForegroundService` 到目标 `Service.onStartCommand` 之间的 IPC 间隙。
     * 生成唯一 token 写入 [intent] extra；调用失败时用返回的 token 调 [releaseHandoff] 立刻归还。
     */
    fun holdHandoff(context: Context, intent: Intent, timeoutMs: Long = HANDOFF_TIMEOUT_MS): Long {
        val appContext = context.applicationContext
        val now = SystemClock.elapsedRealtime()
        val token = synchronized(handoffGuard) {
            pruneExpiredLocked(now)
            val id = ++nextHandoffToken
            val lock = handoffLock ?: appContext
                .getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG_HANDOFF)
                ?.apply { setReferenceCounted(false) }
                ?.also { handoffLock = it }
            if (lock != null && runCatching { lock.acquire(timeoutMs) }.isSuccess) {
                pendingHandoffs[id] = now + timeoutMs
                id
            } else {
                0L
            }
        }
        if (token > 0L) {
            intent.putExtra(EXTRA_HANDOFF_TOKEN, token)
        }
        return token
    }

    fun releaseHandoff(intent: Intent?) {
        val token = intent?.getLongExtra(EXTRA_HANDOFF_TOKEN, 0L) ?: 0L
        if (token > 0L) releaseHandoff(token)
    }

    fun releaseHandoff(token: Long) {
        if (token <= 0L) return
        val now = SystemClock.elapsedRealtime()
        synchronized(handoffGuard) {
            pendingHandoffs.remove(token)
            pruneExpiredLocked(now)
            if (pendingHandoffs.isEmpty()) {
                handoffLock.releaseQuietly()
            }
        }
    }

    private fun pruneExpiredLocked(now: Long) {
        val iterator = pendingHandoffs.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value <= now) {
                iterator.remove()
            }
        }
    }
}
