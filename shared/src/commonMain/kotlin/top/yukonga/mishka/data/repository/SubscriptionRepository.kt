package top.yukonga.mishka.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.yukonga.mishka.data.database.ImportedDao
import top.yukonga.mishka.data.database.ImportedEntity
import top.yukonga.mishka.data.database.PendingDao
import top.yukonga.mishka.data.database.PendingEntity
import top.yukonga.mishka.data.database.SelectionDao
import top.yukonga.mishka.data.model.ProfileType
import top.yukonga.mishka.data.model.Subscription
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProfileFileManager
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.StorageKeys
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 订阅 DB 层管理器。文件层操作由 ProfileProcessor 编排（pending → processing → imported）。
 *
 * 状态机：
 *   CREATE → Pending ✓, Imported ∅
 *     → COMMIT（fetch+validate）→ Imported ✓, Pending ∅
 *     → RELEASE（放弃）→ 全部清除
 *
 *   PATCH（编辑已导入）→ Imported ✓, Pending ✓
 *     → COMMIT → Imported ✓（更新）, Pending ∅
 *     → RELEASE → Imported ✓（不变）, Pending ∅
 *
 *   UPDATE（手动/自动更新）→ 直接更新 Imported
 *   DELETE → 两表都删除
 */
class SubscriptionRepository(
    private val importedDao: ImportedDao,
    private val pendingDao: PendingDao,
    private val selectionDao: SelectionDao,
    private val storage: PlatformStorage,
    private val fileManager: ProfileFileManager? = null,
    scope: CoroutineScope,
) {

    private val profileLock = Mutex()
    private val _activeUuid = MutableStateFlow(storage.getString(StorageKeys.ACTIVE_PROFILE_UUID, ""))
    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    init {
        scope.launch {
            combine(importedDao.getAllFlow(), _activeUuid) { entities, activeId ->
                entities.map { resolveProfile(it, activeId) }
            }.collect { subs ->
                _subscriptions.value = subs
            }
        }
    }

    /** 暴露给 ProfileProcessor 用的 profileLock —— DB 快照与 commit 期间持有。 */
    suspend fun <T> withProfileLock(block: suspend () -> T): T = profileLock.withLock { block() }

    suspend fun queryPending(uuid: String): PendingEntity? = pendingDao.queryByUUID(uuid)
    suspend fun queryImported(uuid: String): ImportedEntity? = importedDao.queryByUUID(uuid)

    // === 两阶段操作 ===

    /**
     * 创建新的 Pending 记录。
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun create(type: ProfileType, name: String, source: String, interval: Long = 0): Subscription = profileLock.withLock {
        val uuid = Uuid.random().toString()
        val pending = PendingEntity(
            uuid = uuid,
            name = name,
            type = type,
            source = source,
            interval = interval,
            createdAt = System.currentTimeMillis(),
        )
        pendingDao.insert(pending)

        Subscription(
            id = uuid,
            name = name,
            type = type,
            url = source,
            imported = false,
            pending = true,
        )
    }

    /**
     * 编辑已有订阅（创建 Pending 副本或更新已有 Pending）。
     */
    suspend fun patch(uuid: String, name: String, source: String, interval: Long) = profileLock.withLock {
        val existing = pendingDao.queryByUUID(uuid)
        if (existing == null) {
            // 从 Imported 创建 Pending 副本
            val imported = importedDao.queryByUUID(uuid)
                ?: throw IllegalArgumentException("Profile $uuid not found")
            pendingDao.insert(
                PendingEntity(
                    uuid = imported.uuid,
                    name = name,
                    type = imported.type,
                    source = source,
                    interval = interval,
                    createdAt = imported.createdAt,
                )
            )
        } else {
            // 更新已有 Pending
            pendingDao.update(
                existing.copy(
                    name = name,
                    source = source,
                    interval = interval,
                    upload = 0,
                    download = 0,
                    total = 0,
                    expire = 0,
                )
            )
        }
    }

    /**
     * 提交 Pending → Imported（DB 层写入；文件层提交由 ProfileProcessor 在 commit 前完成）。
     * 必须在 withProfileLock 内调用以保证 snapshot 一致性。
     */
    suspend fun commitPending(
        uuid: String,
        upload: Long = 0,
        download: Long = 0,
        total: Long = 0,
        expire: Long = 0,
    ) {
        val pending = pendingDao.queryByUUID(uuid)
            ?: throw IllegalArgumentException("No pending profile for $uuid")
        val existingImported = importedDao.queryByUUID(uuid)

        val imported = ImportedEntity(
            uuid = uuid,
            name = pending.name,
            type = pending.type,
            source = pending.source,
            interval = pending.interval,
            upload = upload,
            download = download,
            total = total,
            expire = expire,
            createdAt = existingImported?.createdAt ?: pending.createdAt,
        )

        if (existingImported != null) {
            importedDao.update(imported)
        } else {
            importedDao.insert(imported)
        }
        pendingDao.remove(uuid)

        // 首个导入自动激活
        if (importedDao.count() == 1) {
            _activeUuid.value = uuid
            storage.putString(StorageKeys.ACTIVE_PROFILE_UUID, uuid)
            storage.putString(StorageKeys.ACTIVE_PROFILE_NAME, imported.name)
            ProxyServiceBridge.requestNotificationRefresh()
        }
    }

    /**
     * 放弃编辑，丢弃 Pending。
     */
    suspend fun release(uuid: String) = profileLock.withLock {
        pendingDao.remove(uuid)
    }

    /**
     * 更新已导入订阅的信息（手动/自动更新后调用）。
     */
    suspend fun updateImported(
        uuid: String,
        name: String? = null,
        upload: Long? = null,
        download: Long? = null,
        total: Long? = null,
        expire: Long? = null,
    ) = profileLock.withLock {
        val existing = importedDao.queryByUUID(uuid) ?: return@withLock
        importedDao.update(
            existing.copy(
                name = name ?: existing.name,
                upload = upload ?: existing.upload,
                download = download ?: existing.download,
                total = total ?: existing.total,
                expire = expire ?: existing.expire,
            )
        )
    }

    /**
     * 删除订阅（同时清除 Imported、Pending、Selection）。
     */
    suspend fun delete(uuid: String) = profileLock.withLock {
        val wasActive = _activeUuid.value == uuid
        importedDao.remove(uuid)
        pendingDao.remove(uuid)
        selectionDao.removeByUUID(uuid)
        if (wasActive) {
            val remaining = importedDao.queryAllUUIDs()
            setActive(remaining.firstOrNull() ?: "")
        }
    }

    /**
     * 复制已导入订阅为新的 Pending（File 类型，无 source URL）。
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun clone(uuid: String): String = profileLock.withLock {
        val imported = importedDao.queryByUUID(uuid)
            ?: throw IllegalArgumentException("Profile $uuid not found")
        val newUuid = Uuid.random().toString()
        pendingDao.insert(
            PendingEntity(
                uuid = newUuid,
                name = imported.name,
                type = ProfileType.File,
                source = "",
                interval = 0,
                createdAt = System.currentTimeMillis(),
            )
        )
        newUuid
    }

    // === 活跃配置管理 ===

    suspend fun setActive(id: String) {
        _activeUuid.value = id
        storage.putString(StorageKeys.ACTIVE_PROFILE_UUID, id)
        val name = _subscriptions.value.find { it.id == id }?.name
            ?: importedDao.queryByUUID(id)?.name
            ?: ""
        storage.putString(StorageKeys.ACTIVE_PROFILE_NAME, name)
        // 通知正在运行的 Service 刷新通知中的订阅名称
        ProxyServiceBridge.requestNotificationRefresh()
    }

    fun getActive(): Subscription? {
        val activeId = _activeUuid.value
        if (activeId.isEmpty()) return null
        return _subscriptions.value.find { it.id == activeId }
    }

    // === 视图解析（Pending 优先于 Imported） ===

    private suspend fun resolveProfile(imported: ImportedEntity, activeId: String): Subscription {
        val pending = pendingDao.queryByUUID(imported.uuid)
        return Subscription(
            id = imported.uuid,
            name = pending?.name ?: imported.name,
            type = pending?.type ?: imported.type,
            url = pending?.source ?: imported.source,
            interval = pending?.interval ?: imported.interval,
            upload = pending?.upload ?: imported.upload,
            download = pending?.download ?: imported.download,
            total = pending?.total ?: imported.total,
            expire = pending?.expire ?: imported.expire,
            updatedAt = fileManager?.getDirectoryLastModified(imported.uuid, pending = true)
                ?: fileManager?.getDirectoryLastModified(imported.uuid, pending = false)
                ?: imported.createdAt,
            isActive = imported.uuid == activeId,
            imported = true,
            pending = pending != null,
        )
    }
}

/**
 * 订阅导入流程的类型化错误。所有错误都带有清晰的中文 message，避免 `e.message == null` 漏到 UI。
 */
sealed class ImportError(message: String) : Exception(message) {
    class HttpStatus(val code: Int, description: String) : ImportError("HTTP $code $description")
    class EmptyBody : ImportError("订阅返回内容为空")
    class InvalidScheme(val source: String) : ImportError("URL 必须以 http:// 或 https:// 开头：$source")
    class InvalidName : ImportError("订阅名称不能为空")
    class IntervalTooSmall : ImportError("自动更新间隔最小 15 分钟（0 表示禁用）")
}

/**
 * I/O 前的字段级校验：name 非空、URL 必须 http(s)、interval 0 或 ≥ 15min。
 */
fun PendingEntity.enforceFieldValid() {
    if (name.isBlank()) throw ImportError.InvalidName()
    if (type == ProfileType.Url) {
        val lower = source.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw ImportError.InvalidScheme(source)
        }
    }
    if (interval != 0L && interval < 15 * 60 * 1000L) throw ImportError.IntervalTooSmall()
}
