package top.yukonga.mishka.platform

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

actual class PlatformStorage(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("mishka_prefs", Context.MODE_PRIVATE)

    actual fun getString(key: String, default: String): String =
        prefs.getString(key, default) ?: default

    actual fun putString(key: String, value: String) {
        prefs.edit { putString(key, value) }
    }

    actual fun getStringSet(key: String, default: Set<String>): Set<String> {
        return try {
            prefs.getStringSet(key, default) ?: default
        } catch (_: ClassCastException) {
            // 兼容旧版逗号分隔 String 格式，读取后自动迁移为 StringSet
            val raw = prefs.getString(key, null) ?: return default
            val migrated = raw.split(",").filter { it.isNotBlank() }.toSet()
            prefs.edit {
                remove(key)
                putStringSet(key, migrated)
            }
            migrated
        }
    }

    actual fun putStringSet(key: String, value: Set<String>) {
        prefs.edit { putStringSet(key, value) }
    }
}
