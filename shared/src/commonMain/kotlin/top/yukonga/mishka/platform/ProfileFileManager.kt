package top.yukonga.mishka.platform

/**
 * 订阅配置文件操作接口。
 * Android 端由 MainActivity 注入实现，Desktop 端使用默认空实现。
 */
interface ProfileFileManager {
    fun saveConfig(uuid: String, content: String)
    fun getDir(uuid: String): String
    fun commitToImported(uuid: String)
    fun releasePending(uuid: String)
    fun deleteDirs(uuid: String)
    fun cloneFiles(sourceUuid: String, targetUuid: String)
    suspend fun validate(workDir: String): String?
}
