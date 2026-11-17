package top.yukonga.mishka.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import top.yukonga.mishka.service.RuntimeOverrideBuilder
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

enum class NetworkAddressType { Unknown, Tun, Lan, Wan }

data class NetworkInfoData(
    val localIp: String = "--",
    val interfaceName: String = "--",
    val addressType: NetworkAddressType = NetworkAddressType.Unknown,
)

class PlatformSystemInfo(context: Context) {

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    private var prevCpuTime = 0L
    private var prevRealTime = 0L

    fun getNetworkInfo(tunMode: TunMode, tunDevice: String): NetworkInfoData {
        return try {
            when (tunMode) {
                TunMode.Vpn -> {
                    // fd VPN 的接口由 Android 命名，地址必须与 VpnService.Builder 同源。
                    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return NetworkInfoData()
                    for (intf in interfaces) {
                        if (!intf.isUp || intf.isLoopback) continue
                        for (address in intf.inetAddresses) {
                            if (address.hostAddress == RuntimeOverrideBuilder.VPN_GATEWAY) {
                                return addressInfo(intf.name, address, isTun = true)
                            }
                        }
                    }
                    NetworkInfoData()
                }

                TunMode.RootTun -> {
                    // 使用运行中 /configs 的设备名，不读可能尚未生效的设置，也不猜 fake-IP 段。
                    if (tunDevice.isBlank()) return NetworkInfoData()
                    val intf = NetworkInterface.getByName(tunDevice) ?: return NetworkInfoData()
                    if (!intf.isUp || intf.isLoopback) return NetworkInfoData()
                    networkInfo(intf.name, intf.inetAddresses.iterator(), isTun = true)
                }

                TunMode.RootTproxy -> {
                    val network = connectivityManager.activeNetwork ?: return NetworkInfoData()
                    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkInfoData()
                    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return NetworkInfoData()
                    val links = connectivityManager.getLinkProperties(network) ?: return NetworkInfoData()
                    val name = links.interfaceName ?: return NetworkInfoData()
                    networkInfo(name, links.linkAddresses.asSequence().map { it.address }.iterator(), isTun = false)
                }
            }
        } catch (_: Exception) {
            NetworkInfoData()
        }
    }

    private fun networkInfo(name: String, addresses: Iterator<InetAddress>, isTun: Boolean): NetworkInfoData {
        var selected: InetAddress? = null
        for (address in addresses) {
            if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isMulticastAddress) continue
            if (selected == null || selected.isLinkLocalAddress && !address.isLinkLocalAddress) selected = address
            if (address is Inet4Address) {
                selected = address
                break
            }
        }
        return addressInfo(name, selected ?: return NetworkInfoData(), isTun)
    }

    private fun addressInfo(name: String, address: InetAddress, isTun: Boolean): NetworkInfoData {
        val bytes = address.address
        val isLocal = address.isSiteLocalAddress || address.isLinkLocalAddress ||
                (address is Inet4Address && bytes[0].toInt() == 100 && (bytes[1].toInt() and 0xff) in 64..127) ||
                (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc)
        return NetworkInfoData(
            localIp = address.hostAddress ?: return NetworkInfoData(),
            interfaceName = name,
            addressType = when {
                isTun -> NetworkAddressType.Tun
                isLocal -> NetworkAddressType.Lan
                else -> NetworkAddressType.Wan
            },
        )
    }

    fun getCpuUsage(pid: Int = -1): Float {
        if (pid <= 0) return -1f
        val line = readProcStat(pid) ?: return -1f
        return parseProcStat(line)
    }

    private fun readProcStat(pid: Int): String? {
        // 直接读取（VPN 模式同 UID 可访问）
        try {
            val line = File("/proc/$pid/stat").bufferedReader().use { it.readLine() }
            if (line != null) return line
        } catch (_: Exception) {
        }
        // fallback: 通过 su 读取（ROOT 模式不同 UID）
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /proc/$pid/stat"))
            val line = process.inputStream.bufferedReader().use { it.readLine() }
            process.waitFor()
            line
        } catch (_: Exception) {
            null
        }
    }

    private fun parseProcStat(line: String): Float {
        return try {
            val parts = line.substringAfterLast(')').trim().split(" ")
            if (parts.size < 13) return -1f
            // utime(14) + stime(15)，索引从 ')' 后第一个字段(state)开始为 0
            val utime = parts[11].toLong()
            val stime = parts[12].toLong()
            val cpuTime = utime + stime
            val realTime = android.os.SystemClock.elapsedRealtime()

            if (prevCpuTime == 0L) {
                prevCpuTime = cpuTime
                prevRealTime = realTime
                return -1f
            }

            val cpuDiff = cpuTime - prevCpuTime
            val timeDiff = realTime - prevRealTime
            prevCpuTime = cpuTime
            prevRealTime = realTime

            if (timeDiff == 0L) 0f
            else (cpuDiff.toFloat() * 10 / timeDiff * 100).coerceIn(0f, 100f)
        } catch (_: Exception) {
            -1f
        }
    }
}
