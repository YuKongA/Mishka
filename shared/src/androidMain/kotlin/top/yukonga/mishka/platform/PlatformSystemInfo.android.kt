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
        // 方案 1：读 /proc/stat（系统总 CPU）
        tryProcStat()?.let { return it }
        // 方案 2：读 /proc/loadavg（系统负载）
        tryLoadAvg()?.let { return it }
        // 方案 3：读 /proc/self/stat（应用进程 CPU）
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
            // 字段 14(utime) + 15(stime) 是进程 CPU 时间（以 clock tick 计）
            val parts = line.substringAfterLast(')').trim().split(" ")
            if (parts.size < 13) return null
            // parts[0] = state, parts[11] = utime (index 13 in full), parts[12] = stime (index 14)
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
            // clock tick 通常是 10ms，realTime 是 1ms
            else (cpuDiff.toFloat() * 10 / timeDiff * 100).coerceIn(0f, 100f)
        } catch (_: Exception) {
            null
        }
    }
}
