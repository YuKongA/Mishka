package top.yukonga.mishka.platform

expect class PlatformStorage {
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
    fun getStringSet(key: String, default: Set<String>): Set<String>
    fun putStringSet(key: String, value: Set<String>)
}
