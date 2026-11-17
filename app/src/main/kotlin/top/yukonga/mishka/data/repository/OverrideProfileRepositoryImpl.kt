package top.yukonga.mishka.data.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.mishka.data.bridge.MishkaCoreBridge
import top.yukonga.mishka.data.bridge.MishkaCoreError
import top.yukonga.mishka.data.store.OverrideProfileStore
import top.yukonga.mishka.data.store.OverrideReplacement
import top.yukonga.mishka.data.store.ProfileTransformWriter
import top.yukonga.mishka.domain.model.OverrideFormat
import top.yukonga.mishka.domain.model.OverrideProfile
import top.yukonga.mishka.domain.model.OverrideSourceType
import top.yukonga.mishka.domain.model.Subscription
import top.yukonga.mishka.domain.model.orderedOverrideIds
import top.yukonga.mishka.domain.repository.OverrideProfileRepository
import top.yukonga.mishka.platform.ProfileFileManager
import java.io.File
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

sealed class OverrideInputError(message: String) : Exception(message) {
    class NameRequired : OverrideInputError("empty override name")
    class InvalidUrl : OverrideInputError("override url must start with http:// or https://")
}

class OverrideProfileRepositoryImpl(
    private val store: OverrideProfileStore,
    private val transformWriter: ProfileTransformWriter,
    private val subscriptions: SubscriptionRepositoryImpl,
    private val fileManager: ProfileFileManager,
    private val proxyResolver: SubscriptionProxyResolver,
    scope: CoroutineScope,
) : OverrideProfileRepository {
    private val lock = Mutex()

    override val profiles: StateFlow<ImmutableList<OverrideProfile>> = store.profiles
        .map { it.toPersistentList() }
        .stateIn(scope, SharingStarted.Eagerly, store.all().toPersistentList())

    override suspend fun isUsedByCurrent(id: String): Boolean =
        subscriptions.loadActiveRuntimeSubscription()?.orderedOverrideIds?.contains(id) == true

    override suspend fun readContent(id: String): String = withContext(Dispatchers.IO) {
        store.find(id)?.let(store::readContent).orEmpty()
    }

    override suspend fun addRemote(name: String, url: String, format: OverrideFormat): OverrideProfile {
        requireName(name); requireUrl(url)
        val content = download(url.trim())
        val profile = newProfile(name, OverrideSourceType.Remote, format, url.trim())
        mutate { store.save(profile, content) }
        return profile
    }

    override suspend fun addLocal(name: String, fileName: String, format: OverrideFormat, content: String): OverrideProfile {
        requireName(name)
        val profile = newProfile(name, OverrideSourceType.Local, format, fileName)
        mutate { store.save(profile, content) }
        return profile
    }

    override suspend fun addBlank(name: String, format: OverrideFormat): OverrideProfile {
        requireName(name)
        val profile = newProfile(name, OverrideSourceType.Local, format, "")
        mutate { store.save(profile, "") }
        return profile
    }

    override suspend fun edit(id: String, name: String, url: String, format: OverrideFormat) = mutate {
        val profile = store.find(id) ?: throw IllegalArgumentException("Override $id not found")
        requireName(name)
        if (profile.isRemote) requireUrl(url)
        val next = profile.copy(
            name = name.trim(),
            sourceLocation = if (profile.isRemote) url.trim() else profile.sourceLocation,
            format = format,
        )
        if (format != profile.format) validateForCurrent(profile, format, null)
        store.save(next)
    }

    override suspend fun saveContent(id: String, content: String) = mutate {
        val profile = store.find(id) ?: throw IllegalArgumentException("Override $id not found")
        validateForCurrent(profile, profile.format, content)
        store.save(profile, content)
    }

    override suspend fun update(id: String) = withContext(Dispatchers.IO) {
        val profile = store.find(id) ?: throw IllegalArgumentException("Override $id not found")
        require(profile.isRemote) { "Override $id is local" }
        val content = download(profile.sourceLocation)
        mutate {
            val current = store.find(id) ?: return@mutate
            if (current.sourceLocation != profile.sourceLocation) return@mutate
            validateForCurrent(current, current.format, content)
            store.save(current.copy(lastUpdatedAt = System.currentTimeMillis()), content)
        }
    }

    override suspend fun delete(id: String) = mutate {
        val current = subscriptions.loadActiveRuntimeSubscription()
        if (current != null && id in current.overrideIds) {
            val candidate = current.copy(
                overrideIds = current.overrideIds.filterNot { it == id }.toPersistentList(),
                overrideSortPreference = current.overrideSortPreference.filterNot { it == id }.toPersistentList(),
            )
            validate(candidate, null)
        }
        subscriptions.removeOverrideReferences(id)
        store.delete(id)
    }

    override suspend fun setSelection(subscriptionId: String, overrideIds: List<String>, sortPreference: List<String>) {
        mutate {
            val subscription = subscriptions.loadRuntimeSubscription(subscriptionId)
                ?: throw IllegalArgumentException("Profile $subscriptionId not found")
            val next =
                subscription.copy(overrideIds = overrideIds.toPersistentList(), overrideSortPreference = sortPreference.toPersistentList())
            validate(next, null)
            subscriptions.setOverrideSelection(subscriptionId, overrideIds, sortPreference)
        }
    }

    private suspend fun mutate(block: suspend () -> Unit) = lock.withLock {
        ProfileProcessor.withProcessLock(block)
    }

    private suspend fun validateForCurrent(profile: OverrideProfile, format: OverrideFormat, content: String?) {
        val current = subscriptions.loadActiveRuntimeSubscription()
            ?.takeIf { profile.id in it.overrideIds } ?: return
        withContext(Dispatchers.IO) {
            val replacement = if (content != null) {
                fileManager.writeMihomoFile(VALIDATE_CONTENT, content)
                OverrideReplacement(profile.id, format, VALIDATE_CONTENT)
            } else if (format != profile.format) {
                OverrideReplacement(profile.id, format, store.contentPath(profile))
            } else null
            try {
                validate(current, replacement)
            } finally {
                if (content != null) File(fileManager.getMihomoWorkDir(), VALIDATE_CONTENT).delete()
            }
        }
    }

    private suspend fun validate(subscription: Subscription, replacement: OverrideReplacement?) = withContext(Dispatchers.IO) {
        val transform = transformWriter.write(subscription, VALIDATE_TRANSFORM, replacement) ?: return@withContext
        try {
            MishkaCoreBridge.validateTransform(
                File(fileManager.getImportedDir(subscription.id)),
                File(transform),
                subscription.ageSecretKey,
            )
        } catch (e: MishkaCoreError) {
            throw ConfigValidationException(e.message.orEmpty().removePrefix("validate config:").trim())
        } finally {
            File(transform).delete()
        }
    }

    private suspend fun download(url: String): String {
        val proxyUrl = proxyResolver.resolve(requireUserToggle = false)
        return HttpClient {
            install(HttpTimeout) { requestTimeoutMillis = DOWNLOAD_TIMEOUT_MS }
            if (proxyUrl != null) engine { proxy = ProxyBuilder.http(Url(proxyUrl)) }
        }.use { client ->
            val response = client.get(url)
            if (!response.status.isSuccess()) throw ImportError.HttpStatus(response.status.value, response.status.description)
            response.bodyAsText()
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun newProfile(name: String, sourceType: OverrideSourceType, format: OverrideFormat, sourceLocation: String) =
        OverrideProfile(
            id = Uuid.random().toHexString(),
            name = name.trim(),
            sourceType = sourceType,
            format = format,
            sourceLocation = sourceLocation,
            createdAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
        )

    private fun requireName(name: String) {
        if (name.isBlank()) throw OverrideInputError.NameRequired()
    }

    private fun requireUrl(url: String) {
        val lower = url.trim().lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) throw OverrideInputError.InvalidUrl()
    }

    private companion object {
        const val VALIDATE_TRANSFORM = "override.validate.json"
        const val VALIDATE_CONTENT = "override.validate.content"
        const val DOWNLOAD_TIMEOUT_MS = 30_000L
    }
}
