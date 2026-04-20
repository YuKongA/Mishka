package top.yukonga.mishka.platform

/**
 * 扫描当前持有非全局私网 IPv4（10/8、172.16/12、192.168/16）的接口名。
 *
 * Android：直接读 `java.net.NetworkInterface`，不走 su，不依赖特定子网段，跨 OEM。
 * Desktop：空实现。
 *
 * 返回结果含主 Wi-Fi（wlan0），用户自行在勾选 Dialog 里排除。</br>
 * 调用可能扫描若干接口，仍建议在 Dispatchers.IO 中使用。
 */
expect fun scanTetherInterfacesAsRoot(): List<String>
