package top.yukonga.mishka.data.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

fun getAppDatabase(): AppDatabase {
    val dbDir = File(System.getProperty("user.home"), ".mishka")
    dbDir.mkdirs()
    val dbFile = File(dbDir, "mishka.db")
    return Room.databaseBuilder<AppDatabase>(
        name = dbFile.absolutePath,
    )
        .setDriver(BundledSQLiteDriver())
        .build()
}
