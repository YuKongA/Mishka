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

    actual fun getStringSet(key: String, default: Set<String>): Set<String> {
        val raw = prefs.get(key, "")
        return if (raw.isEmpty()) default else raw.split("\n").filter { it.isNotEmpty() }.toSet()
    }

    actual fun putStringSet(key: String, value: Set<String>) {
        prefs.put(key, value.joinToString("\n"))
        prefs.flush()
    }
}
