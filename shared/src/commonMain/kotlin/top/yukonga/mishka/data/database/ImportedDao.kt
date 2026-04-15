package top.yukonga.mishka.data.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportedDao {
    @Query("SELECT * FROM imported ORDER BY createdAt")
    fun getAllFlow(): Flow<List<ImportedEntity>>

    @Query("SELECT * FROM imported WHERE uuid = :uuid")
    suspend fun queryByUUID(uuid: String): ImportedEntity?

    @Query("SELECT uuid FROM imported ORDER BY createdAt")
    suspend fun queryAllUUIDs(): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ImportedEntity)

    @Update
    suspend fun update(entity: ImportedEntity)

    @Query("DELETE FROM imported WHERE uuid = :uuid")
    suspend fun remove(uuid: String)

    @Query("SELECT EXISTS(SELECT 1 FROM imported WHERE uuid = :uuid)")
    suspend fun exists(uuid: String): Boolean

    @Query("SELECT COUNT(*) FROM imported")
    suspend fun count(): Int
}
