package com.nexa.ai.data.remote

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed interface ChatEvent {
    data class Delta(val text: String) : ChatEvent
    data class Done(val reason: String?) : ChatEvent
    data class Failure(val message: String) : ChatEvent
}

class ChatClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun stream(
        baseUrl: String,
        apiKey: String,
        request: ChatRequest
    ): Flow<ChatEvent> = callbackFlow {        val body = ApiJson.json.encodeToString(ChatRequest.serializer(), request)
            .toRequestBody("application/json".toMediaType())
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(body)
            .build()

        val cancelled = AtomicBoolean(false)
        val source = EventSources.createFactory(client).newEventSource(
            httpRequest,
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    if (cancelled.get()) return
                    if (data == "[DONE]") {
                        trySend(ChatEvent.Done("stop"))
                        close()
                        return
                    }
                    runCatching {
                        val resp = json.decodeFromString(StreamResponse.serializer(), data)
                        resp.choices.firstOrNull()?.delta?.content?.let { deltaText ->
                            if (deltaText.isNotEmpty()) trySend(ChatEvent.Delta(deltaText))
                        }
                    }.onFailure {
                        trySend(ChatEvent.Failure("Failed to parse response: ${it.message}"))
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    if (!cancelled.get()) {
                        trySend(ChatEvent.Done(null))
                        close()
                    }
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                    if (cancelled.get()) return
                    val message = if (response != null) {
                        val raw = runCatching { response.body?.string() }.getOrNull()
                        val parsed = raw?.let {
                            runCatching { json.decodeFromString(ErrorBody.serializer(), it).error?.message }.getOrNull()
                        }
                        parsed ?: "HTTP ${response.code}: ${raw?.take(300) ?: t?.message ?: "request failed"}"
                    } else {
                        t?.message ?: "Unknown network error"
                    }
                    trySend(ChatEvent.Failure(message))
                    close()
                }
            }
        )

        awaitClose {
            cancelled.set(true)
            source.cancel()
        }
    }

    sealed interface CompleteResult {
        data class Success(val raw: kotlinx.serialization.json.JsonObject) : CompleteResult
        data class Failed(val message: String) : CompleteResult
    }

    fun complete(
        baseUrl: String,
        apiKey: String,
        request: ChatRequest
    ): CompleteResult {
        val body = ApiJson.json.encodeToString(ChatRequest.serializer(), request)
            .toRequestBody("application/json".toMediaType())
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(body)
            .build()
        return runCatching {
            client.newCall(httpRequest).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val parsed = runCatching { json.decodeFromString(ErrorBody.serializer(), raw).error?.message }.getOrNull()
                    return CompleteResult.Failed(parsed ?: "HTTP ${response.code}: ${raw.take(300)}")
                }
                val obj = json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject
                    ?: return CompleteResult.Failed("Unexpected response format")
                CompleteResult.Success(obj)
            }
        }.getOrElse { CompleteResult.Failed(it.message ?: "Network error") }
    }
}
