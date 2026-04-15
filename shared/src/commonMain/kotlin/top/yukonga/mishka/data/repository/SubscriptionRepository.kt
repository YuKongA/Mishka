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
import top.yukonga.mishka.data.model.Subscription
import top.yukonga.mishka.platform.PlatformStorage
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 两阶段订阅管理器（对齐 CMFA ProfileManager）。
 *
 * 状态机：
 *   CREATE → Pending ✓, Imported ∅
 *     → COMMIT（下载+校验）→ Imported ✓, Pending ∅
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
    scope: CoroutineScope,
) {

    private val profileLock = Mutex()
    private val _activeUuid = MutableStateFlow(storage.getString("active_profile_uuid", ""))
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

    // === 两阶段操作 ===

    /**
     * 创建新的 Pending 记录。
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun create(type: String, name: String, source: String): Subscription = profileLock.withLock {
        val uuid = Uuid.random().toString().take(8)
        val pending = PendingEntity(
            uuid = uuid,
            name = name,
            type = type,
            source = source,
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
     * 提交 Pending → Imported（在 commit 前由调用方完成下载和校验）。
     */
    suspend fun commit(
        uuid: String,
        upload: Long = 0,
        download: Long = 0,
        total: Long = 0,
        expire: Long = 0,
    ) = profileLock.withLock {
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
            setActive(uuid)
        }
    }

    /**
     * 放弃编辑，丢弃 Pending。
     */
    suspend fun release(uuid: String) = profileLock.withLock {
        val hasImported = importedDao.exists(uuid)
        pendingDao.remove(uuid)
        // 如果没有 Imported 记录（纯新建后放弃），不需要额外处理
        if (!hasImported) {
            // 纯新建后放弃，无需其他操作
        }
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
     * 复制已导入订阅为新的 Pending。
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun clone(uuid: String): String = profileLock.withLock {
        val imported = importedDao.queryByUUID(uuid)
            ?: throw IllegalArgumentException("Profile $uuid not found")
        val newUuid = Uuid.random().toString().take(8)
        pendingDao.insert(
            PendingEntity(
                uuid = newUuid,
                name = imported.name,
                type = "File",
                source = imported.source,
                interval = imported.interval,
                createdAt = System.currentTimeMillis(),
            )
        )
        newUuid
    }

    // === 活跃配置管理 ===

    fun setActive(id: String) {
        _activeUuid.value = id
        storage.putString("active_profile_uuid", id)
        val name = _subscriptions.value.find { it.id == id }?.name ?: ""
        storage.putString("active_profile_name", name)
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
            updatedAt = imported.createdAt,
            isActive = imported.uuid == activeId,
            imported = true,
            pending = pending != null,
        )
    }
}
