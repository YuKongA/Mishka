package top.yukonga.mishka.data.api

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import top.yukonga.mishka.data.model.ConnectionsResponse
import top.yukonga.mishka.data.model.LogMessage
import top.yukonga.mishka.data.model.MemoryData
import top.yukonga.mishka.data.model.TrafficData

class MihomoWebSocket(
    private val apiClient: MihomoApiClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val wsClient = HttpClient {
        install(WebSockets)
    }

    fun trafficFlow(): Flow<TrafficData> = webSocketFlow(
        apiClient.getWebSocketUrl("/traffic"),
    ) { text -> json.decodeFromString<TrafficData>(text) }

    fun logsFlow(level: String = "info"): Flow<LogMessage> = webSocketFlow(
        apiClient.getWebSocketUrl("/logs") + "&level=$level",
    ) { text -> json.decodeFromString<LogMessage>(text) }

    fun memoryFlow(): Flow<MemoryData> = webSocketFlow(
        apiClient.getWebSocketUrl("/memory"),
    ) { text -> json.decodeFromString<MemoryData>(text) }

    fun connectionsFlow(): Flow<ConnectionsResponse> = webSocketFlow(
        apiClient.getWebSocketUrl("/connections"),
    ) { text -> json.decodeFromString<ConnectionsResponse>(text) }

    private fun <T> webSocketFlow(url: String, parser: (String) -> T): Flow<T> = flow {
        wsClient.webSocket(url) {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    try {
                        emit(parser(frame.readText()))
                    } catch (_: Exception) {
                        // 跳过解析失败的帧
                    }
                }
            }
        }
    }

    fun close() {
        wsClient.close()
    }
}
