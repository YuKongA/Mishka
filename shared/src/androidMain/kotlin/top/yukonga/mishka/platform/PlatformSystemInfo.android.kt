package top.yukonga.mishka.platform

import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

actual class PlatformSystemInfo actual constructor() {

    private var prevCpuTime = 0L
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

    actual fun getCpuUsage(pid: Int): Float {
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
