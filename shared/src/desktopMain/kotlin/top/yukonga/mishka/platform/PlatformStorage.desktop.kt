package top.yukonga.mishka.platform

import java.util.prefs.Preferences

actual class PlatformStorage {
    private val prefs: Preferences = Preferences.userNodeForPackage(PlatformStorage::class.java)

    actual fun getString(key: String, default: String): String =
        prefs.get(key, default)

    actual fun putString(key: String, value: String) {
        prefs.put(key, value)
        prefs.flush()
    }
}
