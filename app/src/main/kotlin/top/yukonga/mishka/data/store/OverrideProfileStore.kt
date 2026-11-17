package top.yukonga.mishka.data.store

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.yukonga.mishka.domain.model.OverrideFormat
import top.yukonga.mishka.domain.model.OverrideProfile
import top.yukonga.mishka.platform.ProfileFileManager
import java.io.File

@Serializable
private data class OverrideListFile(val overrides: List<OverrideProfile> = emptyList())

@Serializable
data class OverrideSpec(
    val name: String,
    val format: String,
    val path: String,
)

data class OverrideReplacement(val id: String, val format: OverrideFormat, val relativePath: String)

/** 覆写元数据与内容文件，均放在 files/mihomo/overrides/。 */
class OverrideProfileStore(
    private val fileManager: ProfileFileManager,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }
    private val listPath = "$DIRECTORY/$LIST_FILE"
    private val _profiles = MutableStateFlow(readList())
    val profiles: Flow<List<OverrideProfile>> = _profiles.asStateFlow()
    private val writeLock = Mutex()

    fun all(): List<OverrideProfile> = _profiles.value
    fun find(id: String): OverrideProfile? = all().firstOrNull { it.id == id }
    fun contentPath(profile: OverrideProfile): String = "$DIRECTORY/${profile.fileName}"
    fun readContent(profile: OverrideProfile): String = fileManager.readMihomoFile(contentPath(profile)).orEmpty()

    suspend fun save(profile: OverrideProfile, content: String? = null) {
        writeLock.withLock {
            val previous = find(profile.id)
            val oldPath = previous?.let(::contentPath)
            val newPath = contentPath(profile)
            if (content != null) {
                fileManager.writeMihomoFile(newPath, content)
            } else if (oldPath != null && oldPath != newPath) {
                fileManager.writeMihomoFile(newPath, fileManager.readMihomoFile(oldPath).orEmpty())
            }
            val next = _profiles.value.toMutableList().apply {
                val index = indexOfFirst { it.id == profile.id }
                if (index >= 0) set(index, profile) else add(profile)
            }
            writeList(next)
            _profiles.value = next
            if (oldPath != null && oldPath != newPath) File(fileManager.getMihomoWorkDir(), oldPath).delete()
        }
    }

    suspend fun delete(id: String) {
        writeLock.withLock {
            val profile = find(id) ?: return
            val next = _profiles.value.filterNot { it.id == id }
            writeList(next)
            _profiles.value = next
            File(fileManager.getMihomoWorkDir(), contentPath(profile)).delete()
        }
    }

    fun specs(ids: List<String>, replacement: OverrideReplacement? = null): List<OverrideSpec> = ids.mapNotNull { id ->
        find(id)?.let { profile ->
            if (replacement?.id == id) {
                OverrideSpec(
                    profile.name,
                    replacement.format.extension,
                    File(fileManager.getMihomoWorkDir(), replacement.relativePath).path
                )
            } else {
                OverrideSpec(profile.name, profile.format.extension, File(fileManager.getMihomoWorkDir(), contentPath(profile)).path)
            }
        }
    }

    fun encodeList(): String = json.encodeToString(OverrideListFile.serializer(), OverrideListFile(all()))

    private fun readList(): List<OverrideProfile> {
        val raw = fileManager.readMihomoFile(listPath).orEmpty()
        return if (raw.isBlank()) emptyList() else runCatching {
            json.decodeFromString<OverrideListFile>(raw).overrides
        }.getOrElse { error ->
            Log.e(TAG, "Failed to parse $listPath; keeping original as a backup", error)
            fileManager.backupMihomoFile(listPath)
            emptyList()
        }
    }

    private fun writeList(value: List<OverrideProfile>) {
        fileManager.writeMihomoFile(listPath, json.encodeToString(OverrideListFile.serializer(), OverrideListFile(value)))
    }

    companion object {
        const val DIRECTORY = "overrides"
        const val LIST_FILE = "overrides_list.json"
        private const val TAG = "OverrideProfileStore"
    }
}
