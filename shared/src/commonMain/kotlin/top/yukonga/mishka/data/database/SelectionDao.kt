package top.yukonga.mishka.data.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface SelectionDao {
    @Query("SELECT * FROM selections WHERE uuid = :uuid")
    suspend fun queryByUUID(uuid: String): List<SelectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SelectionEntity)

    @Query("DELETE FROM selections WHERE uuid = :uuid")
    suspend fun removeByUUID(uuid: String)
}
