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

    override suspend fun validate(workDir: String, onProgress: ((String) -> Unit)?): String? {
        return MihomoValidator.validate(context, workDir, onProgress)
    }

    override fun ensureGeodataAvailable(workDir: String) {
        ProfileFileOps.ensureGeodataLinks(context, java.io.File(workDir))
    }

    override fun collectGeodata(workDir: String) {
        ProfileFileOps.collectGeodataFiles(context, java.io.File(workDir))
    }
}
