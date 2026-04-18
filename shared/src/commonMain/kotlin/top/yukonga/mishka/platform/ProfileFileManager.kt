package top.yukonga.mishka.platform

/**
 * 订阅配置文件操作接口。三阶段目录：
 *
 * - pending/{uuid}/    草稿，create/edit 阶段写入
 * - processing/        单例沙箱，每次 prepareProcessing 清空后从 pending 复制；fetch / providers 下载 / mihomo -t 全部在此完成
 * - imported/{uuid}/   commit 后从 processing 拷贝到这里，作为最终运行配置
 */
interface ProfileFileManager {
    // === per-uuid pending ===
    fun savePendingConfig(uuid: String, content: String)
    fun releasePending(uuid: String)

    // === 单例 processing 沙箱 ===
    /** 清空 processing/，复制 pending/{uuid}/ → processing/，返回 processing 绝对路径。 */
    fun prepareProcessing(uuid: String): String

    /** 覆盖写 processing 目录下的 config.yaml（fetch 后调用）。 */
    fun writeProcessingConfig(workDir: String, content: String)

    /** 删除 processing/ 全部内容（失败回滚或启动时清理调用）。 */
    fun cleanupProcessing()

    // === 提交 ===
    /** 清 imported/{uuid}/ → 复制 processing/ → imported/{uuid}/ → 删 pending/{uuid}/。 */
    fun commitProcessingToImported(uuid: String)

    // === 校验（基于 processing 工作目录） ===
    suspend fun validate(
        workDir: String,
        configFileName: String = "config.yaml",
        onProgress: ((String) -> Unit)? = null,
    ): String?

    /**
     * 使用 mihomo -prefetch 预下载所有 HTTP provider 到 workDir。best-effort：
     * 失败时返回 false，不抛异常——运行期 mihomo pullLoop 仍会兜底重试。
     */
    suspend fun prefetch(
        workDir: String,
        configFileName: String = "config.yaml",
        onProgress: ((String) -> Unit)? = null,
    ): Boolean

    /** mihomo 运行时工作目录（files/mihomo/），存放 override.user.json 等通用文件。 */
    fun getMihomoWorkDir(): String

    /** 读 mihomo workDir 下的文件（如 override.user.json），文件不存在返回 null。 */
    fun readMihomoFile(relativePath: String): String?

    /** 写 mihomo workDir 下的文件（覆盖写入）。 */
    fun writeMihomoFile(relativePath: String, content: String)

    fun ensureGeodataAvailable(workDir: String)
    fun collectGeodata(workDir: String)

    // === imported 目录操作 ===
    fun getImportedDir(uuid: String): String
    fun getDirectoryLastModified(uuid: String, pending: Boolean): Long?
    fun listImportedFiles(uuid: String): List<String>
    fun readImportedFile(uuid: String, relativePath: String): String?
    fun writeImportedFile(uuid: String, relativePath: String, content: String)
    fun deleteDirs(uuid: String)

    /** 复制 imported/{sourceUuid}/ → pending/{targetUuid}/，供 duplicate 流程使用。 */
    fun cloneFiles(sourceUuid: String, targetUuid: String)
}
