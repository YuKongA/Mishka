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

    override fun getMihomoWorkDir(): String = ConfigGenerator.getWorkDir(context).absolutePath

    override fun readMihomoFile(relativePath: String): String? {
        val file = File(ConfigGenerator.getWorkDir(context), relativePath)
        return if (file.exists()) file.readText() else null
    }

    override fun writeMihomoFile(relativePath: String, content: String) {
        ProfileFileOps.writeAtomically(File(ConfigGenerator.getWorkDir(context), relativePath), content)
    }

    override fun backupMihomoFile(relativePath: String) {
        val file = File(ConfigGenerator.getWorkDir(context), relativePath)
        if (!file.exists()) return
        val backup = File(file.parentFile, "${file.name}.bak")
        backup.delete()
        file.renameTo(backup)
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

    override suspend fun deleteDirs(uuid: String) {
        ProfileFileOps.deleteProfileDirs(context, uuid)
    }

    override suspend fun deleteOrphanDirs(knownUuids: Set<String>): List<String> {
        return ProfileFileOps.deleteOrphanProfileDirs(context, knownUuids)
    }
}
