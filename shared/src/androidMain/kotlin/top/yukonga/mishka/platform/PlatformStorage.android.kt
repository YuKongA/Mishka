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

    actual fun getStringSet(key: String, default: Set<String>): Set<String> =
        prefs.getStringSet(key, default) ?: default

    actual fun putStringSet(key: String, value: Set<String>) {
        prefs.edit { putStringSet(key, value) }
    }
}
