package top.yukonga.mishka.data.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update

@Dao
interface PendingDao {
    @Query("SELECT * FROM pending WHERE uuid = :uuid")
    suspend fun queryByUUID(uuid: String): PendingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PendingEntity)

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(entity: PendingEntity)

    @Query("DELETE FROM pending WHERE uuid = :uuid")
    suspend fun remove(uuid: String)
}
