package top.yukonga.mishka.platform

expect class PlatformStorage {
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
    fun getStringSet(key: String, default: Set<String>): Set<String>
    fun putStringSet(key: String, value: Set<String>)
}

object StorageKeys {
    // 服务状态
    const val SERVICE_WAS_RUNNING = "service_was_running"
    const val TUN_MODE = "tun_mode"
    const val HAS_ROOT = "has_root"

    // ROOT 模式持久化
    const val ROOT_MIHOMO_PID = "root_mihomo_pid"
    const val ROOT_MIHOMO_SECRET = "root_mihomo_secret"
    const val ROOT_START_TIME = "root_start_time"
    const val ROOT_ACTIVE_SUBSCRIPTION_ID = "root_active_subscription_id"

    // 订阅
    const val ACTIVE_PROFILE_UUID = "active_profile_uuid"
    const val ACTIVE_PROFILE_NAME = "active_profile_name"
    const val SUBSCRIPTION_UPDATE_VIA_PROXY = "subscription_update_via_proxy"

    // VPN 设置
    const val VPN_BYPASS_PRIVATE_NETWORK = "vpn_bypass_private_network"
    const val VPN_ALLOW_BYPASS = "vpn_allow_bypass"
    const val VPN_DNS_HIJACKING = "vpn_dns_hijacking"
    const val VPN_SYSTEM_PROXY = "vpn_system_proxy"
    const val VPN_ALLOW_IPV6 = "vpn_allow_ipv6"

    // 分应用代理
    const val APP_PROXY_MODE = "app_proxy_mode"
    const val APP_PROXY_PACKAGES = "app_proxy_packages"

    // ROOT 设置
    const val ROOT_TUN_DEVICE = "root_tun_device"
    const val ROOT_TETHER_MODE = "root_tether_mode"
    const val ROOT_TETHER_IFACES = "root_tether_ifaces"
    // 启动时生效的 tether mode 快照；attach 路径用它判断 app 被杀期间用户是否改过模式
    const val ROOT_TETHER_MODE_ACTIVE = "root_tether_mode_active"
    // 启动时生效的 ROOT submode 快照（"tun"/"tproxy"）；attach 路径按此与当前 TUN_MODE 比对
    const val ROOT_SUBMODE_ACTIVE = "root_submode_active"

    // 通用设置
    const val DARK_MODE = "dark_mode"
    const val DYNAMIC_NOTIFICATION = "dynamic_notification"
    const val PREDICTIVE_BACK = "predictive_back"

    // 一次性迁移标记
    const val MIGRATION_ROOT_RECLAIM_DONE = "migration_root_reclaim_done"
}
