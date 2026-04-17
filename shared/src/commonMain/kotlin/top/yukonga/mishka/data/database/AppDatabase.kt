package top.yukonga.mishka.data.database

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.TypeConverters

@Database(
    entities = [ImportedEntity::class, PendingEntity::class, SelectionEntity::class],
    version = 1,
)
@TypeConverters(ProfileTypeConverter::class)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun importedDao(): ImportedDao
    abstract fun pendingDao(): PendingDao
    abstract fun selectionDao(): SelectionDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase>
