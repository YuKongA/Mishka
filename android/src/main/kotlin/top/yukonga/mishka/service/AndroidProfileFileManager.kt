package top.yukonga.mishka.service

import android.content.Context
import top.yukonga.mishka.platform.ProfileFileManager
import java.io.File

class AndroidProfileFileManager(private val context: Context) : ProfileFileManager {

    override fun savePendingConfig(uuid: String, content: String) {
        ProfileFileOps.savePendingConfig(context, uuid, content)
    }

    override fun releasePending(uuid: String) {
        ProfileFileOps.releasePending(context, uuid)
    }

    override fun prepareProcessing(uuid: String): String {
        return ProfileFileOps.prepareProcessing(context, uuid).absolutePath
    }

    override fun writeProcessingConfig(workDir: String, content: String) {
        ProfileFileOps.writeProcessingConfig(workDir, content)
    }

    override fun cleanupProcessing() {
        ProfileFileOps.cleanupProcessing(context)
    }

    override fun commitProcessingToImported(uuid: String) {
        ProfileFileOps.commitProcessingToImported(context, uuid)
    }

    override suspend fun validate(workDir: String, configFileName: String, onProgress: ((String) -> Unit)?): String? {
        return MihomoValidator.validate(context, workDir, configFileName, onProgress)
    }

    override fun getMihomoWorkDir(): String = ConfigGenerator.getWorkDir(context).absolutePath

    override fun readMihomoFile(relativePath: String): String? {
        val file = File(ConfigGenerator.getWorkDir(context), relativePath)
        return if (file.exists()) file.readText() else null
    }

    override fun writeMihomoFile(relativePath: String, content: String) {
        val file = File(ConfigGenerator.getWorkDir(context), relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    override fun ensureGeodataAvailable(workDir: String) {
        ProfileFileOps.ensureGeodataLinks(context, File(workDir))
    }

    override fun collectGeodata(workDir: String) {
        ProfileFileOps.collectGeodataFiles(context, File(workDir))
    }

    override fun getImportedDir(uuid: String): String {
        return ProfileFileOps.getImportedDir(context, uuid).absolutePath
    }

    override fun getDirectoryLastModified(uuid: String, pending: Boolean): Long? {
        return ProfileFileOps.getProfileDirLastModified(context, uuid, pending)
    }

    override fun listImportedFiles(uuid: String): List<String> {
        return ProfileFileOps.listImportedFiles(context, uuid)
    }

    override fun readImportedFile(uuid: String, relativePath: String): String? {
        return ProfileFileOps.readImportedFile(context, uuid, relativePath)
    }

    override fun writeImportedFile(uuid: String, relativePath: String, content: String) {
        ProfileFileOps.writeImportedFile(context, uuid, relativePath, content)
    }

    override fun deleteDirs(uuid: String) {
        ProfileFileOps.deleteProfileDirs(context, uuid)
    }

    override fun cloneFiles(sourceUuid: String, targetUuid: String) {
        ProfileFileOps.cloneImportedToPending(context, sourceUuid, targetUuid)
    }
}
