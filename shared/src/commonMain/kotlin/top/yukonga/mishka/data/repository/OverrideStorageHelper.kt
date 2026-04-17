package top.yukonga.mishka.data.repository

import top.yukonga.mishka.platform.PlatformStorage

/**
 * 覆写设置的 PlatformStorage 读写助手。
 * 所有覆写 key 使用 "override_" 前缀，值为字符串形式：
 * - 三态 Boolean: "null" / "true" / "false"
 * - 可空 Int: "null" / 数字字符串
 * - 可空 String: "null" / 值字符串
 * - 可空 List<String>: "null" / 换行分隔的字符串
 */
object OverrideStorageHelper {

    // === Key 常量 ===

    // 通用
    const val KEY_HTTP_PORT = "override_http_port"
    const val KEY_SOCKS_PORT = "override_socks_port"
    const val KEY_REDIR_PORT = "override_redir_port"
    const val KEY_TPROXY_PORT = "override_tproxy_port"
    const val KEY_MIXED_PORT = "override_mixed_port"
    const val KEY_ALLOW_LAN = "override_allow_lan"
    const val KEY_IPV6 = "override_ipv6"
    const val KEY_EXTERNAL_CONTROLLER = "override_external_controller"
    const val KEY_BIND_ADDRESS = "override_bind_address"
    const val KEY_LOG_LEVEL = "override_log_level"
    const val KEY_MODE = "override_mode"
    const val KEY_TUN_STACK = "override_tun_stack"

    // DNS
    const val KEY_DNS_ENABLE = "override_dns_enable"
    const val KEY_DNS_LISTEN = "override_dns_listen"
    const val KEY_DNS_IPV6 = "override_dns_ipv6"
    const val KEY_DNS_PREFER_H3 = "override_dns_prefer_h3"
    const val KEY_DNS_USE_HOSTS = "override_dns_use_hosts"
    const val KEY_DNS_ENHANCED_MODE = "override_dns_enhanced_mode"
    const val KEY_DNS_NAMESERVERS = "override_dns_nameservers"
    const val KEY_DNS_FALLBACK = "override_dns_fallback"
    const val KEY_DNS_DEFAULT_NAMESERVER = "override_dns_default_nameserver"
    const val KEY_DNS_FAKEIP_FILTER = "override_dns_fakeip_filter"

    // Meta 特性
    const val KEY_UNIFIED_DELAY = "override_unified_delay"
    const val KEY_GEODATA_MODE = "override_geodata_mode"
    const val KEY_TCP_CONCURRENT = "override_tcp_concurrent"
    const val KEY_FIND_PROCESS_MODE = "override_find_process_mode"

    // Sniffer
    const val KEY_SNIFFER_ENABLE = "override_sniffer_enable"
    const val KEY_SNIFFER_FORCE_DNS_MAPPING = "override_sniffer_force_dns_mapping"
    const val KEY_SNIFFER_PARSE_PURE_IP = "override_sniffer_parse_pure_ip"
    const val KEY_SNIFFER_OVERRIDE_DEST = "override_sniffer_override_dest"
    const val KEY_SNIFFER_FORCE_DOMAIN = "override_sniffer_force_domain"
    const val KEY_SNIFFER_SKIP_DOMAIN = "override_sniffer_skip_domain"

    // === 读写方法 ===

    fun readNullableBoolean(storage: PlatformStorage, key: String): Boolean? {
        return when (storage.getString(key, "null")) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    fun writeNullableBoolean(storage: PlatformStorage, key: String, value: Boolean?) {
        storage.putString(key, value?.toString() ?: "null")
    }

    fun readNullableInt(storage: PlatformStorage, key: String): Int? {
        val str = storage.getString(key, "null")
        if (str == "null") return null
        return str.toIntOrNull()
    }

    fun writeNullableInt(storage: PlatformStorage, key: String, value: Int?) {
        storage.putString(key, value?.toString() ?: "null")
    }

    fun readNullableString(storage: PlatformStorage, key: String): String? {
        val str = storage.getString(key, "null")
        if (str == "null") return null
        return str
    }

    fun writeNullableString(storage: PlatformStorage, key: String, value: String?) {
        storage.putString(key, value ?: "null")
    }

    fun readNullableStringList(storage: PlatformStorage, key: String): List<String>? {
        val str = storage.getString(key, "null")
        if (str == "null") return null
        if (str.isBlank()) return emptyList()
        return str.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun writeNullableStringList(storage: PlatformStorage, key: String, value: List<String>?) {
        if (value == null) {
            storage.putString(key, "null")
        } else {
            storage.putString(key, value.joinToString("\n"))
        }
    }
}
