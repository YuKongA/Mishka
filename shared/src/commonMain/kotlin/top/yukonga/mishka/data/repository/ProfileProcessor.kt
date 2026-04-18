package top.yukonga.mishka.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.mishka.data.model.ProfileType
import top.yukonga.mishka.data.model.Subscription
import top.yukonga.mishka.platform.ProfileFileManager

/**
 * 单条订阅导入进度（UI 用）。
 */
data class ImportProgress(
    val step: String,
    val current: Int = 0,
    val total: Int = 0,
)

/**
 * mihomo -t 校验失败时抛出，message 为子进程 stderr/stdout 提取的错误信息。
 */
class ConfigValidationException(message: String) : Exception(message)

/**
 * 订阅处理器：snapshot → prepareProcessing → fetch → providers → validate → commit-swap。
 * processLock 串行所有处理流程，SubscriptionRepository.profileLock 保护 DB snapshot 一致性。
 */
class ProfileProcessor(
    private val repo: SubscriptionRepository,
    private val fileManager: ProfileFileManager,
    private val fetcher: SubscriptionFetcher,
) {

    private val processLock = Mutex()

    /** 提交一个 Pending 订阅（新建或编辑后调用）。 */
    suspend fun apply(uuid: String, onProgress: (ImportProgress) -> Unit = {}) {
        runProcess(uuid, isUpdate = false, onProgress)
    }

    /** 刷新已导入的订阅（直接拉取新内容并替换 Imported，跳过 Pending DB 记录）。 */
    suspend fun update(uuid: String, onProgress: (ImportProgress) -> Unit = {}) {
        runProcess(uuid, isUpdate = true, onProgress)
    }

    private suspend fun runProcess(
        uuid: String,
        isUpdate: Boolean,
        onProgress: (ImportProgress) -> Unit,
    ) = withContext(NonCancellable + Dispatchers.Default) {
        processLock.withLock {
            // 阶段 1: 快照 + 准备 processing/
            val (snapshot, workDir) = repo.withProfileLock {
                if (isUpdate) {
                    val imported = repo.queryImported(uuid)
                        ?: throw IllegalArgumentException("Profile $uuid not found")
                    val snap = PendingSnapshot(imported.uuid, imported.name, imported.type, imported.source, imported.interval)
                    val dir = fileManager.prepareProcessing(uuid)
                    // update 路径：把 imported/{uuid}/config.yaml 拉进 processing/ 作为基准
                    fileManager.readImportedFile(uuid, "config.yaml")?.let {
                        fileManager.writeProcessingConfig(dir, it)
                    }
                    snap to dir
                } else {
                    val pending = repo.queryPending(uuid)
                        ?: throw IllegalArgumentException("No pending profile for $uuid")
                    pending.enforceFieldValid()
                    val dir = fileManager.prepareProcessing(uuid)
                    PendingSnapshot(pending.uuid, pending.name, pending.type, pending.source, pending.interval) to dir
                }
            }

            try {
                var upload = 0L
                var download = 0L
                var total = 0L
                var expire = 0L
                var resolvedName = snapshot.name

                // 阶段 2: fetch（仅 Url 类型；File 类型已由 savePendingConfig 在 create 时写入 pending）
                if (snapshot.type == ProfileType.Url) {
                    onProgress(ImportProgress("下载配置..."))
                    val result = fetcher.fetch(snapshot.toSubscription())
                    fileManager.writeProcessingConfig(workDir, result.configContent)
                    upload = result.subscription.upload
                    download = result.subscription.download
                    total = result.subscription.total
                    expire = result.subscription.expire
                    if (result.subscription.name.isNotBlank()) resolvedName = result.subscription.name
                }

                // 阶段 3: 校验（providers 由 mihomo -t 自身按需下载）
                // mihomo -t 只做 parse + provider 校验，不 bind 端口不 init TUN，
                // 订阅内 tun.enable: true 也不会失败；直接校验订阅原文，无需写 override 合并的临时文件
                fileManager.ensureGeodataAvailable(workDir)
                onProgress(ImportProgress("验证配置..."))
                val err = fileManager.validate(workDir, "config.yaml") {
                    onProgress(ImportProgress(it))
                }
                if (err != null) throw ConfigValidationException(err)

                // 阶段 4: 提交（snapshot 一致性检查 + 文件 swap + DB 更新）
                repo.withProfileLock {
                    if (isUpdate) {
                        val current = repo.queryImported(uuid)
                            ?: throw IllegalArgumentException("Imported profile $uuid disappeared during update")
                        check(current.uuid == snapshot.uuid)
                        fileManager.commitProcessingToImported(uuid)
                        fileManager.collectGeodata(fileManager.getImportedDir(uuid))
                        repo.updateImported(
                            uuid = uuid,
                            name = if (resolvedName != snapshot.name) resolvedName else null,
                            upload = upload,
                            download = download,
                            total = total,
                            expire = expire,
                        )
                    } else {
                        val currentPending = repo.queryPending(uuid)
                            ?: throw IllegalArgumentException("Pending profile $uuid disappeared during commit")
                        check(currentPending.uuid == snapshot.uuid)
                        fileManager.commitProcessingToImported(uuid)
                        fileManager.collectGeodata(fileManager.getImportedDir(uuid))
                        repo.commitPending(uuid, upload, download, total, expire)
                    }
                }
            } catch (t: Throwable) {
                fileManager.cleanupProcessing()
                throw t
            }
        }
    }

}

/**
 * 处理流程内部使用的快照。与 PendingEntity 解耦：update 路径下不存在 Pending 记录，但仍需带上原始字段做提交。
 */
internal data class PendingSnapshot(
    val uuid: String,
    val name: String,
    val type: ProfileType,
    val source: String,
    val interval: Long,
) {
    fun toSubscription(): Subscription = Subscription(
        id = uuid,
        name = name,
        type = type,
        url = source,
        interval = interval,
    )
}
