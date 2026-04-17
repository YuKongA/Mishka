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

    /**
     * 使用 mihomo -t 校验指定工作目录下的配置文件。
     * @param configFileName 相对 workDir 的配置文件名，默认校验订阅原始 config.yaml；
     *                       调用方传 config.validate.yaml 可校验 override 合并后的 YAML
     */
    suspend fun validate(workDir: String, configFileName: String = "config.yaml", onProgress: ((String) -> Unit)? = null): String?

    /**
     * 生成 override 合并后的校验专用配置，写入订阅目录并返回文件名（相对 workDir）。
     * 供 processAndCommit 在 validate 前调用 —— 确保被校验的 YAML 即将被实际运行。
     */
    fun generateValidationConfig(uuid: String): String

    /** 清理 generateValidationConfig 产生的临时文件。在 try/finally 中调用避免污染目录。 */
    fun cleanupValidationConfig(uuid: String)

    fun ensureGeodataAvailable(workDir: String)
    fun collectGeodata(workDir: String)
}
