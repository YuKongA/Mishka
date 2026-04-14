package top.yukonga.mishka.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.yukonga.mishka.data.model.Subscription
import top.yukonga.mishka.platform.PlatformStorage
import java.util.UUID

class SubscriptionRepository(private val storage: PlatformStorage) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    fun load() {
        val data = storage.getString("subscriptions", "[]")
        _subscriptions.value = try {
            json.decodeFromString<List<Subscription>>(data)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save() {
        storage.putString("subscriptions", json.encodeToString(_subscriptions.value))
    }

    fun add(name: String, url: String): Subscription {
        val sub = Subscription(
            id = UUID.randomUUID().toString().take(8),
            name = name,
            url = url,
            isActive = _subscriptions.value.isEmpty(),
        )
        _subscriptions.value = _subscriptions.value + sub
        save()
        return sub
    }

    fun remove(id: String) {
        val wasActive = _subscriptions.value.find { it.id == id }?.isActive == true
        _subscriptions.value = _subscriptions.value.filter { it.id != id }
        if (wasActive && _subscriptions.value.isNotEmpty()) {
            setActive(_subscriptions.value.first().id)
        }
        save()
    }

    fun update(subscription: Subscription) {
        _subscriptions.value = _subscriptions.value.map {
            if (it.id == subscription.id) subscription else it
        }
        save()
    }

    fun setActive(id: String) {
        _subscriptions.value = _subscriptions.value.map {
            it.copy(isActive = it.id == id)
        }
        save()
    }

    fun getActive(): Subscription? = _subscriptions.value.find { it.isActive }
}
