package top.yukonga.mishka.data.database

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

@Volatile
private var INSTANCE: AppDatabase? = null

fun getAppDatabase(context: Context): AppDatabase {
    return INSTANCE ?: synchronized(AppDatabase::class) {
        INSTANCE ?: Room.databaseBuilder<AppDatabase>(
            context = context.applicationContext,
            name = context.getDatabasePath("mishka.db").absolutePath,
        )
            .setDriver(BundledSQLiteDriver())
            .build()
            .also { INSTANCE = it }
    }
}
