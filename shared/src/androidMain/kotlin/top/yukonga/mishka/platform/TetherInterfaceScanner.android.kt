package top.yukonga.mishka.platform

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 扫描当前持有非全局私网 IPv4 的接口名，排除主 Wi-Fi / 蜂窝 / TUN / 回环。
 *
 * 不走 `su ip -o addr show`（su PATH 因 ROM 而异，且热点子网并非所有 OEM 都用 192.168.43.x）。
 * 直接读 `NetworkInterface.getNetworkInterfaces()`，系统内核视图，无需 root。
 *
 * 过滤规则：
 * - UP 状态
 * - 至少有一个非回环 IPv4 站点本地地址（10/8、172.16/12、192.168/16）
 * - 排除 lo、tun*、utun*、rmnet*、dummy*、p2p*、bt-pan 的主 STA 候选（wlan0/wlan0 默认不排，用户若误选可手动去掉）
 */
actual fun scanTetherInterfacesAsRoot(): List<String> {
    return try {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { iface ->
                iface.isUp && !iface.isLoopback && isCandidateName(iface.name) && hasSiteLocalIpv4(iface)
            }
            .map { it.name }
            .distinct()
    } catch (e: Exception) {
        Log.w("TetherScanner", "enumerate interfaces failed", e)
        emptyList()
    }
}

private fun isCandidateName(name: String): Boolean {
    if (name.startsWith("tun")) return false
    if (name.startsWith("utun")) return false
    if (name.startsWith("rmnet")) return false
    if (name.startsWith("dummy")) return false
    if (name.startsWith("p2p")) return false
    if (name.startsWith("ccmni")) return false   // 联发科蜂窝
    if (name == "lo") return false
    return true
}

private fun hasSiteLocalIpv4(iface: NetworkInterface): Boolean {
    val addresses = iface.inetAddresses ?: return false
    for (addr in addresses) {
        if (addr !is Inet4Address) continue
        if (addr.isLoopbackAddress) continue
        if (addr.isLinkLocalAddress) continue   // 169.254/16 忽略
        if (addr.isSiteLocalAddress) return true
    }
    return false
}
