package com.nexa.ai.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val tool_calls: List<ToolCallDto> = emptyList(),
    val tool_call_id: String? = null,
    val name: String? = null
)

@Serializable
data class FunctionDto(val name: String, val arguments: String)

@Serializable
data class ToolCallDto(val id: String, val type: String = "function", val function: FunctionDto)

@Serializable
data class ToolDto(val type: String = "function", val function: FunctionSchema)

@Serializable
data class FunctionSchema(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double,
    val stream: Boolean = true,
    val tools: List<ToolDto> = emptyList()
)

@Serializable
data class ChoiceDelta(val content: String? = null)

@Serializable
data class StreamChoice(val delta: ChoiceDelta? = null, val finish_reason: String? = null)

@Serializable
data class StreamResponse(val choices: List<StreamChoice> = emptyList())

@Serializable
data class Choice(val message: ChatMessage)

@Serializable
data class ChatResponse(val choices: List<Choice> = emptyList())

@Serializable
data class ErrorBody(val error: ErrorDetail? = null)

@Serializable
data class ErrorDetail(val message: String? = null)

object ApiJson {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }
}
