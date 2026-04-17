package top.yukonga.mishka.data.database

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import top.yukonga.mishka.data.model.ProfileType
import top.yukonga.mishka.data.model.Subscription
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys

object DataMigration {

    private val json = Json { ignoreUnknownKeys = true }

    fun migrateIfNeeded(storage: PlatformStorage, database: AppDatabase) {
        val oldData = storage.getString("subscriptions", "")
        if (oldData.isBlank() || oldData == "[]") return

        runBlocking {
            val importedDao = database.importedDao()
            if (importedDao.count() > 0) return@runBlocking

            try {
                val subscriptions = json.decodeFromString<List<Subscription>>(oldData)
                subscriptions.forEach { sub ->
                    val entity = ImportedEntity(
                        uuid = sub.id,
                        name = sub.name,
                        type = if (sub.url.isNotEmpty()) ProfileType.Url else ProfileType.File,
                        source = sub.url,
                        upload = sub.upload,
                        download = sub.download,
                        total = sub.total,
                        expire = sub.expire,
                        createdAt = sub.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    )
                    importedDao.insert(entity)

                    if (sub.isActive) {
                        storage.putString(StorageKeys.ACTIVE_PROFILE_UUID, sub.id)
                    }
                }
                storage.putString("subscriptions", "")
            } catch (_: Exception) {
                // 迁移失败时静默处理，不影响应用启动
            }
        }
    }
}
