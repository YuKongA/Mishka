package top.yukonga.mishka.data.database

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "imported")
data class ImportedEntity(
    @PrimaryKey val uuid: String,
    val name: String,
    val type: String,
    val source: String,
    val interval: Long = 0,
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,
    val createdAt: Long,
)
