package top.yukonga.mishka.service

import android.content.Context
import top.yukonga.mishka.platform.ProfileFileManager

class AndroidProfileFileManager(private val context: Context) : ProfileFileManager {
    override fun saveConfig(uuid: String, content: String) {
        ProfileFileOps.saveSubscriptionConfig(context, uuid, content)
    }

    override fun getDir(uuid: String): String {
        return ProfileFileOps.getSubscriptionDir(context, uuid).absolutePath
    }

    override fun commitToImported(uuid: String) {
        ProfileFileOps.commitPendingToImported(context, uuid)
    }

    override fun releasePending(uuid: String) {
        ProfileFileOps.releasePending(context, uuid)
    }

    override fun deleteDirs(uuid: String) {
        ProfileFileOps.deleteProfileDirs(context, uuid)
    }

    override fun cloneFiles(sourceUuid: String, targetUuid: String) {
        ProfileFileOps.cloneImportedToPending(context, sourceUuid, targetUuid)
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

    override suspend fun validate(workDir: String, configFileName: String, onProgress: ((String) -> Unit)?): String? {
        return MihomoValidator.validate(context, workDir, configFileName, onProgress)
    }

    override fun generateValidationConfig(uuid: String): String {
        ConfigGenerator.writeValidationConfig(context, uuid)
        return ConfigGenerator.VALIDATION_CONFIG_NAME
    }

    override fun cleanupValidationConfig(uuid: String) {
        val file = java.io.File(ProfileFileOps.getSubscriptionDir(context, uuid), ConfigGenerator.VALIDATION_CONFIG_NAME)
        if (file.exists()) file.delete()
    }

    override fun ensureGeodataAvailable(workDir: String) {
        ProfileFileOps.ensureGeodataLinks(context, java.io.File(workDir))
    }

    override fun collectGeodata(workDir: String) {
        ProfileFileOps.collectGeodataFiles(context, java.io.File(workDir))
    }
}
