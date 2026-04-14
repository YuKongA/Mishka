package top.yukonga.mishka.platform

expect class PlatformStorage {
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
}
