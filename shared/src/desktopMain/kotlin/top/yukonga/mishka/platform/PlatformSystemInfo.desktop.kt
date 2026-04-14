package top.yukonga.mishka.platform

import java.lang.management.ManagementFactory
import java.net.Inet4Address
import java.net.NetworkInterface

actual class PlatformSystemInfo actual constructor() {

    actual fun getNetworkInfo(): NetworkInfoData {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return NetworkInfoData()
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return NetworkInfoData(
                            localIp = addr.hostAddress ?: "0.0.0.0",
                            interfaceName = intf.name,
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }
        return NetworkInfoData()
    }

    actual fun getCpuUsage(): Float {
        try {
            val osBean = ManagementFactory.getOperatingSystemMXBean()
            val load = osBean.systemLoadAverage
            if (load < 0) return -1f
            val processors = osBean.availableProcessors
            return (load / processors * 100).toFloat().coerceIn(0f, 100f)
        } catch (_: Exception) {
            return -1f
        }
    }
}
