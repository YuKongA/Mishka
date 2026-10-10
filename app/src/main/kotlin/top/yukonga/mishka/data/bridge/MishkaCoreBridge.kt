package top.yukonga.mishka.data.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** age x25519 密钥对：secretKey 用于解密订阅，publicKey 供加密方使用。 */
data class AgeKeyPair(val secretKey: String, val publicKey: String)

/** action: FetchConfiguration / FetchProviders / Verifying；args 视 action 而定。 */
@Serializable
data class CoreFetchProgress(
    val action: String,
    val args: List<String> = emptyList(),
    val progress: Int = -1,
    val max: Int = -1,
)

@Serializable
data class CoreFetchResult(
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,
    val fileName: String = "",
)

@Serializable
private data class TransformCheck(val valid: Boolean = false)

class MishkaCoreError(message: String) : RuntimeException(message)

object MishkaCoreBridge {

    private val tokenSeq = AtomicInteger(1)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        // mishka_jni 链接依赖 libmihomo.so 的导出符号，必须先加载。
        System.loadLibrary("mihomo")
        System.loadLibrary("mishka_jni")
    }

    fun init(homeDir: String, userAgent: String) {
        nativeCoreInit(homeDir, userAgent)
    }

    suspend fun fetchAndValid(
        workDir: String,
        url: String,
        force: Boolean,
        httpProxy: String?,
        userAgent: String,
        ageSecretKey: String,
        onProgress: suspend (CoreFetchProgress) -> Unit,
    ): CoreFetchResult = coroutineScope {
        val token = tokenSeq.getAndIncrement()
        val nativeDone = AtomicBoolean(false)
        // 取消钩子必须**先于**阻塞调用挂上：JNI 调用不响应协程取消，等 withContext 抛
        // CancellationException 时 Go 侧 defer 已清空 cancelRegistry，那时再调就是 no-op。
        // 这条 watcher 在被取消的瞬间就让 Go ctx 进入 Done，native 随即带错返回。
        val canceller = launch {
            try {
                awaitCancellation()
            } finally {
                if (!nativeDone.get()) runCatching { nativeCancel(token) }
            }
        }
        val pollerJob = launchProgressPoller(this, token, onProgress)
        try {
            val raw = withContext(Dispatchers.IO) {
                // age 全局密钥进程级共享：fetchAndValid 由 processLock 串行，fetch 前设置、后清空。
                nativeSetAgeSecretKey(ageSecretKey)
                try {
                    nativeFetchAndValid(workDir, url, force, httpProxy, userAgent, token)
                } finally {
                    nativeDone.set(true)
                    nativeSetAgeSecretKey("")
                }
            }
            interpretResult(raw)
        } finally {
            // canceller 停在 awaitCancellation，不显式取消会让 coroutineScope 永不返回
            canceller.cancel()
            pollerJob.cancel()
        }
    }

    private fun launchProgressPoller(
        scope: CoroutineScope,
        token: Int,
        onProgress: suspend (CoreFetchProgress) -> Unit,
    ) = scope.launch(Dispatchers.IO) {
        var last: String? = null
        while (isActive) {
            val current = nativeQueryProgress(token)
            if (current != null && current != last) {
                last = current
                runCatching { json.decodeFromString(CoreFetchProgress.serializer(), current) }
                    .onSuccess { onProgress(it) }
            }
            delay(PROGRESS_POLL_INTERVAL_MS)
        }
    }

    private fun interpretResult(raw: String?): CoreFetchResult {
        if (raw.isNullOrEmpty()) throw MishkaCoreError("native returned empty result")
        if (raw.startsWith("error:")) {
            throw MishkaCoreError(raw.removePrefix("error:").trim())
        }
        return runCatching { json.decodeFromString(CoreFetchResult.serializer(), raw) }
            .getOrElse { throw MishkaCoreError("invalid native payload: $raw") }
    }

    /** 按运行时同一条解密、脚本、Parse 链路校验变换，保存失败时不污染覆写文件。 */
    fun validateTransform(workDir: java.io.File, transform: java.io.File, ageSecretKey: String) {
        val raw = nativeValidateTransform(workDir.path, transform.path, ageSecretKey)
        val result = interpretTransformResult(raw)
        check(result.valid) { "native transform validation returned invalid result" }
    }

    private fun interpretTransformResult(raw: String?): TransformCheck {
        if (raw.isNullOrEmpty()) throw MishkaCoreError("native returned empty result")
        if (raw.startsWith("error:")) throw MishkaCoreError(raw.removePrefix("error:").trim())
        return runCatching { json.decodeFromString(TransformCheck.serializer(), raw) }
            .getOrElse { throw MishkaCoreError("invalid native payload: $raw") }
    }

    @JvmStatic
    private external fun nativeValidateTransform(
        workDir: String,
        transform: String,
        secretKey: String,
    ): String?

    @JvmStatic
    private external fun nativeCoreInit(homeDir: String, userAgent: String)

    @JvmStatic
    private external fun nativeFetchAndValid(
        workDir: String,
        url: String,
        force: Boolean,
        httpProxy: String?,
        userAgent: String,
        token: Int,
    ): String?

    @JvmStatic
    private external fun nativeCancel(token: Int)

    @JvmStatic
    private external fun nativeQueryProgress(token: Int): String?

    fun generateAgeKeyPair(hybrid: Boolean = false): AgeKeyPair? {
        val raw = (if (hybrid) nativeGenAgeHybridKeyPair() else nativeGenAgeKeyPair()) ?: return null
        if (raw.startsWith("error:")) return null
        val lines = raw.split("\n")
        if (lines.size < 2) return null
        val secret = lines[0].trim()
        val public = lines[1].trim()
        if (secret.isEmpty() || public.isEmpty()) return null
        return AgeKeyPair(secretKey = secret, publicKey = public)
    }

    @JvmStatic
    private external fun nativeSetAgeSecretKey(key: String)

    @JvmStatic
    private external fun nativeGenAgeKeyPair(): String?

    @JvmStatic
    private external fun nativeGenAgeHybridKeyPair(): String?

    private const val PROGRESS_POLL_INTERVAL_MS = 150L
}
