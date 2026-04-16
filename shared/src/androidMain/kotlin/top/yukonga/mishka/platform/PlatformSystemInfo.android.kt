package top.yukonga.mishka.platform

import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

actual class PlatformSystemInfo actual constructor() {

    private var prevIdle = 0L
    private var prevTotal = 0L
    private var prevAppCpuTime = 0L
    private var prevRealTime = 0L

    actual fun getNetworkInfo(): NetworkInfoData {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return NetworkInfoData()
            var fallback: NetworkInfoData? = null
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: "0.0.0.0"
                        val info = NetworkInfoData(localIp = ip, interfaceName = intf.name)
                        // 优先返回 TUN 接口（198.18.0.0/15 地址段）
                        if (ip.startsWith("198.18.") || ip.startsWith("198.19.")) {
                            return info
                        }
                        if (fallback == null) {
                            fallback = info
                        }
                    }
                }
            }
            return fallback ?: NetworkInfoData()
        } catch (_: Exception) {
        }
        return NetworkInfoData()
    }

    actual fun getCpuUsage(): Float {
        tryProcStat()?.let { return it }
        tryLoadAvg()?.let { return it }
        trySelfStat()?.let { return it }
        return -1f
    }

    private fun tryProcStat(): Float? {
        return try {
            val line = File("/proc/stat").bufferedReader().use { it.readLine() } ?: return null
            val values = line.substringAfter("cpu").trim().split("\\s+".toRegex()).map { it.toLong() }
            if (values.size < 4) return null

            val idle = values[3] + (values.getOrNull(4) ?: 0L)
            val total = values.sum()

            if (prevTotal == 0L) {
                prevIdle = idle
                prevTotal = total
                return null
            }

            val diffIdle = idle - prevIdle
            val diffTotal = total - prevTotal
            prevIdle = idle
            prevTotal = total

            if (diffTotal == 0L) 0f
            else (diffTotal - diffIdle).toFloat() / diffTotal * 100
        } catch (_: Exception) {
            null
        }
    }

    private fun tryLoadAvg(): Float? {
        return try {
            val line = File("/proc/loadavg").bufferedReader().use { it.readLine() } ?: return null
            val load = line.split(" ")[0].toFloat()
            val cpus = Runtime.getRuntime().availableProcessors()
            (load / cpus * 100).coerceIn(0f, 100f)
        } catch (_: Exception) {
            null
        }
    }

    private fun trySelfStat(): Float? {
        return try {
            val line = File("/proc/self/stat").bufferedReader().use { it.readLine() } ?: return null
            val parts = line.substringAfterLast(')').trim().split(" ")
            if (parts.size < 13) return null
            val utime = parts[11].toLong()
            val stime = parts[12].toLong()
            val cpuTime = utime + stime
            val realTime = android.os.SystemClock.elapsedRealtime()

            if (prevAppCpuTime == 0L) {
                prevAppCpuTime = cpuTime
                prevRealTime = realTime
                return null
            }

            val cpuDiff = cpuTime - prevAppCpuTime
            val timeDiff = realTime - prevRealTime
            prevAppCpuTime = cpuTime
            prevRealTime = realTime

            if (timeDiff == 0L) 0f
            else (cpuDiff.toFloat() * 10 / timeDiff * 100).coerceIn(0f, 100f)
        } catch (_: Exception) {
            null
        }
    }
}
