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
     * 返回订阅目录的最后修改时间（毫秒）。
     * @param pending true 取 pending/{uuid}，false 取 imported/{uuid}
     * @return 目录存在且 mtime > 0 返回时间戳，否则 null
     */
    fun getDirectoryLastModified(uuid: String, pending: Boolean): Long?

    /** 列出 imported/{uuid} 下的文件（相对路径）。目录不存在返回空列表。 */
    fun listImportedFiles(uuid: String): List<String>

    /** 读取 imported/{uuid}/{relativePath} 文本内容，不存在或读失败返回 null。 */
    fun readImportedFile(uuid: String, relativePath: String): String?

    /** 写入 imported/{uuid}/{relativePath} 文本内容。调用方负责先校验 YAML 合法性。 */
    fun writeImportedFile(uuid: String, relativePath: String, content: String)

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
