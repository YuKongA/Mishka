package top.yukonga.mishka.platform

data class NetworkInfoData(
    val localIp: String = "0.0.0.0",
    val interfaceName: String = "--",
)

expect class PlatformSystemInfo() {
    fun getNetworkInfo(): NetworkInfoData
    fun getCpuUsage(pid: Int = -1): Float
}
