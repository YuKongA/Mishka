package top.yukonga.mishka.data.repository

import android.util.Log
import androidx.room3.withWriteTransaction
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.yukonga.mishka.data.database.AppDatabase
import top.yukonga.mishka.data.database.ImportedDao
import top.yukonga.mishka.data.database.ImportedEntity
import top.yukonga.mishka.data.database.PendingDao
import top.yukonga.mishka.data.database.PendingEntity
import top.yukonga.mishka.data.database.SelectionDao
import top.yukonga.mishka.data.database.decodeOverrideIds
import top.yukonga.mishka.data.database.encodeOverrideIds
import top.yukonga.mishka.domain.model.ProfileType
import top.yukonga.mishka.domain.model.Subscription
import top.yukonga.mishka.domain.model.SubscriptionInfo
import top.yukonga.mishka.domain.repository.SubscriptionRepository
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProfileFileManager
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.StorageKeys
import kotlin.time.Clock
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
 *   DELETE → 三表都删除（imported / pending / selections）
 */
class SubscriptionRepositoryImpl(
    private val importedDao: ImportedDao,
    private val pendingDao: PendingDao,
    private val selectionDao: SelectionDao,
    private val storage: PlatformStorage,
    private val fileManager: ProfileFileManager? = null,
    private val scope: CoroutineScope,
    private val database: AppDatabase,
) : SubscriptionRepository {

    private val profileLock = Mutex()
    private val _activeUuid = MutableStateFlow(storage.getString(StorageKeys.ACTIVE_PROFILE_UUID, ""))
    private val _subscriptions = MutableStateFlow<ImmutableList<Subscription>>(persistentListOf())
    override val subscriptions: StateFlow<ImmutableList<Subscription>> = _subscriptions.asStateFlow()

    /**
     * mihomo runtime 聚合后的 active 订阅 live 流量。绑定 subscriptionId 避免用户切 active
     * 不 restart 时把旧 active 的 provider 数据归属到新 active。HomeViewModel 在 loadConfig
     * 拿到 providers 后推一次；mihomo 停止时清空。
     */
    private val _liveProvider = MutableStateFlow<LiveProviderSnapshot?>(null)

    /** 当前活跃订阅（合并 mihomo runtime 聚合数据后），订阅页和主页流量栏统一从此读 */
    override val activeSubscription: StateFlow<Subscription?> = _subscriptions
        .map { list -> list.firstOrNull { it.isActive } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * 推送 mihomo runtime 聚合后的 live provider 流量。HomeViewModel 在 loadConfig 成功后
     * 调用；service 停止 / repository 断开时传 null 清空。subscriptionId 必传以做归属校验。
     */
    override fun setLiveProviderInfo(subscriptionId: String?, info: SubscriptionInfo?) {
        _liveProvider.value = if (subscriptionId != null && info != null) {
            LiveProviderSnapshot(subscriptionId, info)
        } else null
    }

    init {
        scope.launch {
            // combine 体内有 DAO 查询与目录 stat，抛出去这条流就永久终结、订阅列表停更。
            // `.catch` 是终结型的救不了，只能整体重订阅
            while (isActive) {
                runCatching { collectProfiles() }.onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.e(TAG, "subscription stream failed, resubscribing", e)
                    delay(RESUBSCRIBE_DELAY_MS)
                }
            }
        }
    }

    private suspend fun collectProfiles() {
        combine(importedDao.getAllFlow(), _activeUuid, _liveProvider) { entities, activeId, live ->
            // pending 一次取回建表：逐条 queryByUUID 会让任意 imported 写入 / active 切换 /
            // live 推送都放大成 N 次查询
            val pendingMap = pendingDao.queryAll().associateBy { it.uuid }
            entities.map { resolveProfile(it, pendingMap[it.uuid], activeId, live) }
                .toPersistentList()
            // 目录 mtime 是阻塞 stat，appScope 跑在 Default
        }.flowOn(Dispatchers.IO).collect { subs ->
            _subscriptions.value = subs
        }
    }

    /** 暴露给 ProfileProcessor 用的 profileLock —— DB 快照与 commit 期间持有。 */
    suspend fun <T> withProfileLock(block: suspend () -> T): T = profileLock.withLock { block() }

    suspend fun queryPending(uuid: String): PendingEntity? = pendingDao.queryByUUID(uuid)
    suspend fun queryImported(uuid: String): ImportedEntity? = importedDao.queryByUUID(uuid)

    // === Pending / Imported 状态迁移 ===

    /**
     * 创建新的 Pending 记录。
     */
    @OptIn(ExperimentalUuidApi::class)
    override suspend fun create(
        type: ProfileType,
        name: String,
        source: String,
        interval: Long,
        userAgent: String,
        ageSecretKey: String,
    ): Subscription = profileLock.withLock {
        val uuid = Uuid.random().toString()
        val trimmedUA = userAgent.trim()
        val trimmedAge = ageSecretKey.trim()
        val pending = PendingEntity(
            uuid = uuid,
            name = name,
            type = type,
            source = source,
            userAgent = trimmedUA,
            ageSecretKey = trimmedAge,
            overrideIds = "",
            overrideSortPreference = "",
            interval = interval,
            createdAt = Clock.System.now().toEpochMilliseconds(),
        )
        pendingDao.insert(pending)

        Subscription(
            id = uuid,
            name = name,
            type = type,
            url = source,
            userAgent = trimmedUA,
            ageSecretKey = trimmedAge,
            overrideIds = persistentListOf(),
            overrideSortPreference = persistentListOf(),
            imported = false,
            pending = true,
        )
    }

    /**
     * 编辑已有订阅（创建 Pending 副本或更新已有 Pending）。
     */
    override suspend fun patch(
        uuid: String,
        name: String,
        source: String,
        interval: Long,
        userAgent: String,
        ageSecretKey: String,
    ) = profileLock.withLock {
        val trimmedUA = userAgent.trim()
        val trimmedAge = ageSecretKey.trim()
        val existing = pendingDao.queryByUUID(uuid)
        if (existing == null) {
            val imported = importedDao.queryByUUID(uuid)
                ?: throw IllegalArgumentException("Profile $uuid not found")
            pendingDao.insert(
                PendingEntity(
                    uuid = imported.uuid,
                    name = name,
                    type = imported.type,
                    source = source,
                    userAgent = trimmedUA,
                    ageSecretKey = trimmedAge,
                    overrideIds = imported.overrideIds,
                    overrideSortPreference = imported.overrideSortPreference,
                    interval = interval,
                    createdAt = imported.createdAt,
                )
            )
        } else {
            pendingDao.update(
                existing.copy(
                    name = name,
                    source = source,
                    userAgent = trimmedUA,
                    ageSecretKey = trimmedAge,
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
        fallbackName: String = "",
    ) {
        val pending = pendingDao.queryByUUID(uuid)
            ?: throw IllegalArgumentException("No pending profile for $uuid")
        val existingImported = importedDao.queryByUUID(uuid)

        // fallbackName 语义见 ProfileProcessor.autoProfileName：仅 pending.name 留空时采用
        val imported = ImportedEntity(
            uuid = uuid,
            name = pending.name.ifBlank { fallbackName },
            type = pending.type,
            source = pending.source,
            userAgent = pending.userAgent,
            ageSecretKey = pending.ageSecretKey,
            overrideIds = pending.overrideIds,
            overrideSortPreference = pending.overrideSortPreference,
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
        }
        // 同步 active 订阅名（首次激活与编辑提交两种路径共用，辅助函数内部判 active 与变化）
        syncActiveNameIfActive(uuid, imported.name)
    }

    /**
     * 放弃编辑，丢弃 Pending。
     */
    override suspend fun release(uuid: String) = profileLock.withLock {
        pendingDao.remove(uuid)
    }

    override suspend fun validatePendingForCommit(uuid: String): Boolean {
        val pending = pendingDao.queryByUUID(uuid) ?: return false
        pending.enforceFieldValid()
        return pending.type == ProfileType.Url && pending.source.isNotBlank()
    }

    override suspend fun commitPendingProfile(uuid: String) = profileLock.withLock {
        commitPending(uuid)
    }

    /**
     * 更新已导入订阅的信息（手动/自动更新后调用）。
     * 必须在 withProfileLock 内调用 —— Mutex 不可重入，自身加锁会与外层 commit 快照锁死锁。
     */
    suspend fun updateImported(
        uuid: String,
        name: String? = null,
        upload: Long? = null,
        download: Long? = null,
        total: Long? = null,
        expire: Long? = null,
    ) {
        val existing = importedDao.queryByUUID(uuid) ?: return
        importedDao.update(
            existing.copy(
                name = name ?: existing.name,
                upload = upload ?: existing.upload,
                download = download ?: existing.download,
                total = total ?: existing.total,
                expire = expire ?: existing.expire,
            )
        )
        if (name != null && name != existing.name) {
            syncActiveNameIfActive(uuid, name)
        }
    }

    /** DB 里现存的全部订阅 uuid（imported + pending），供启动时反扫孤儿目录。 */
    suspend fun knownUuids(): Set<String> = profileLock.withLock {
        (importedDao.queryAllUUIDs() + pendingDao.queryAllUUIDs()).toSet()
    }

    /**
     * Service 冷启动时不能依赖 subscriptions StateFlow 已完成首轮收集；直接从 DB 读出
     * 当前生效字段，保证开机自启也能带上脚本覆写。
     */
    suspend fun loadRuntimeSubscription(uuid: String): Subscription? = withProfileLock {
        val imported = importedDao.queryByUUID(uuid) ?: return@withProfileLock null
        Subscription(
            id = uuid,
            name = imported.name,
            type = imported.type,
            url = imported.source,
            userAgent = imported.userAgent,
            ageSecretKey = imported.ageSecretKey,
            overrideIds = imported.overrideIds.decodeOverrideIds(),
            overrideSortPreference = imported.overrideSortPreference.decodeOverrideIds(),
            interval = imported.interval,
            isActive = _activeUuid.value == uuid,
            imported = true,
        )
    }

    suspend fun loadActiveRuntimeSubscription(): Subscription? =
        _activeUuid.value.takeIf { it.isNotEmpty() }?.let { loadRuntimeSubscription(it) }

    /** 保存订阅的覆写选择；顺序字段与内容文件一起作为运行时快照。 */
    override suspend fun setOverrideSelection(
        subscriptionId: String,
        overrideIds: List<String>,
        sortPreference: List<String>,
    ) = profileLock.withLock {
        val encodedIds = overrideIds.encodeOverrideIds()
        val encodedOrder = sortPreference.encodeOverrideIds()
        database.withWriteTransaction {
            val imported = importedDao.queryByUUID(subscriptionId)
                ?: throw IllegalArgumentException("Profile $subscriptionId not found")
            importedDao.update(
                imported.copy(
                    overrideIds = encodedIds,
                    overrideSortPreference = encodedOrder,
                ),
            )
            pendingDao.queryByUUID(subscriptionId)?.let { pending ->
                pendingDao.update(
                    pending.copy(
                        overrideIds = encodedIds,
                        overrideSortPreference = encodedOrder,
                    ),
                )
            }
            Unit
        }
    }

    /** 删除覆写时同时清理 imported 与 pending，避免草稿取消后复活悬空引用。 */
    suspend fun removeOverrideReferences(id: String) = profileLock.withLock {
        database.withWriteTransaction {
            importedDao.queryAll().forEach { profile ->
                val ids = profile.overrideIds.decodeOverrideIds()
                val order = profile.overrideSortPreference.decodeOverrideIds()
                if (id in ids || id in order) {
                    importedDao.update(
                        profile.copy(
                            overrideIds = ids.filterNot { it == id }.encodeOverrideIds(),
                            overrideSortPreference = order.filterNot { it == id }.encodeOverrideIds(),
                        ),
                    )
                }
            }
            pendingDao.queryAll().forEach { profile ->
                val ids = profile.overrideIds.decodeOverrideIds()
                val order = profile.overrideSortPreference.decodeOverrideIds()
                if (id in ids || id in order) {
                    pendingDao.update(
                        profile.copy(
                            overrideIds = ids.filterNot { it == id }.encodeOverrideIds(),
                            overrideSortPreference = order.filterNot { it == id }.encodeOverrideIds(),
                        ),
                    )
                }
            }
        }
    }

    /**
     * 删除订阅（同时清除 Imported、Pending、Selection）。
     */
    override suspend fun delete(uuid: String) = profileLock.withLock {
        val wasActive = _activeUuid.value == uuid
        importedDao.remove(uuid)
        pendingDao.remove(uuid)
        selectionDao.removeByUUID(uuid)
        if (wasActive) {
            val remaining = importedDao.queryAllUUIDs()
            setActive(remaining.firstOrNull() ?: "")
        }
    }

    // === 活跃配置管理 ===

    // uuid 同步写：调用方紧接着的 restartProxy 需要立刻读到新值，不能延后到下一个调度 tick
    override fun setActive(id: String) {
        _activeUuid.value = id
        storage.putString(StorageKeys.ACTIVE_PROFILE_UUID, id)
        scope.launch {
            val name = _subscriptions.value.find { it.id == id }?.name
                ?: importedDao.queryByUUID(id)?.name
                ?: ""
            storage.putString(StorageKeys.ACTIVE_PROFILE_NAME, name)
            ProxyServiceBridge.requestNotificationRefresh()
        }
    }

    override fun getActive(): Subscription? {
        val activeId = _activeUuid.value
        if (activeId.isEmpty()) return null
        return _subscriptions.value.find { it.id == activeId }
    }

    override fun getActiveId(): String? = _activeUuid.value.ifEmpty { null }

    /**
     * 当 uuid 是当前 active 时，同步 storage 中缓存的订阅名并触发通知刷新。
     * 仅在 name 实际变化时 emit，避免周期性流量更新打断通知动画。
     * 实现纯同步（storage write + tryEmit），允许在 profileLock 内调用。
     */
    private fun syncActiveNameIfActive(uuid: String, name: String) {
        if (_activeUuid.value != uuid) return
        if (storage.getString(StorageKeys.ACTIVE_PROFILE_NAME, "") == name) return
        storage.putString(StorageKeys.ACTIVE_PROFILE_NAME, name)
        ProxyServiceBridge.requestNotificationRefresh()
    }

    // === 视图解析（Pending 优先于 Imported；active 命中 live snapshot 时优先 live 流量） ===

    private fun resolveProfile(
        imported: ImportedEntity,
        pending: PendingEntity?,
        activeId: String,
        live: LiveProviderSnapshot?,
    ): Subscription {
        // 用户可编辑字段优先级：编辑中的 Pending > DB ImportedEntity。
        //
        // **流量四项跳过 pending 层**：pending 的这些字段在所有写入路径上都是 0（create 用默认值、
        // patch 显式清零），叠进优先级就会让「只要有草稿，已用/总量/到期全变 0」——编辑订阅后
        // fetch 失败留下残留 pending 即触发。它们只有 live > imported 两层：模板订阅（顶层 URL 无
        // subscription-userinfo header）DB 流量为 0，真实流量在 mihomo runtime 的 proxy-provider 里
        val liveInfo = live?.takeIf { it.subscriptionId == imported.uuid }?.info
        return Subscription(
            id = imported.uuid,
            name = pending?.name ?: imported.name,
            type = pending?.type ?: imported.type,
            url = pending?.source ?: imported.source,
            userAgent = pending?.userAgent ?: imported.userAgent,
            ageSecretKey = pending?.ageSecretKey ?: imported.ageSecretKey,
            overrideIds = (pending?.overrideIds ?: imported.overrideIds).decodeOverrideIds(),
            overrideSortPreference = (pending?.overrideSortPreference ?: imported.overrideSortPreference).decodeOverrideIds(),
            interval = pending?.interval ?: imported.interval,
            upload = liveInfo?.Upload ?: imported.upload,
            download = liveInfo?.Download ?: imported.download,
            total = liveInfo?.Total ?: imported.total,
            expire = liveInfo?.Expire ?: imported.expire,
            // 无草稿就不去 stat pending/ —— 该目录只随 pending 行一同创建
            updatedAt = (if (pending != null) fileManager?.getDirectoryLastModified(imported.uuid, pending = true) else null)
                ?: fileManager?.getDirectoryLastModified(imported.uuid, pending = false)
                ?: imported.createdAt,
            isActive = imported.uuid == activeId,
            imported = true,
            pending = pending != null,
        )
    }

    private companion object {
        const val TAG = "SubscriptionRepository"
        const val RESUBSCRIBE_DELAY_MS = 1000L
    }
}

/** active 订阅的 mihomo runtime 聚合流量快照，绑定 subscriptionId 防止切 active 时错误归属。 */
data class LiveProviderSnapshot(
    val subscriptionId: String,
    val info: SubscriptionInfo,
)

/**
 * 订阅导入流程的类型化错误。message 是给日志看的英文技术描述，**用户可见文案由 UI 层按类型映射**
 * （见 `SubscriptionViewModel.localizedMessage`）——data 层拿不到 locale，也不该决定怎么说人话。
 */
sealed class ImportError(message: String) : Exception(message) {
    class HttpStatus(val code: Int, description: String) : ImportError("HTTP $code $description")
    class EmptyBody : ImportError("empty response body")
    class InvalidScheme(val source: String) : ImportError("unsupported scheme: $source")
    class InvalidName : ImportError("empty profile name")
    class IntervalTooSmall : ImportError("auto-update interval below minimum")
}

/**
 * I/O 前的字段级校验：name 非空（Url 型例外——留空走 fetch 后自动命名）、
 * URL 必须 http(s)、interval 0 或 ≥ 15min。
 */
fun PendingEntity.enforceFieldValid() {
    if (name.isBlank() && type != ProfileType.Url) throw ImportError.InvalidName()
    if (type == ProfileType.Url) {
        val lower = source.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw ImportError.InvalidScheme(source)
        }
    }
    if (interval != 0L && interval < 15 * 60 * 1000L) throw ImportError.IntervalTooSmall()
}
