package com.nexa.ai.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object AgentProtocol {

    const val MAX_STEPS = 8

    fun systemRules(): String = """
        |You are in WORK MODE with full access to the user's chosen workspace folder on their device.
        |You act as an autonomous file agent. Available tools:
        |
        |<tool>{"name":"list_files","args":{"path":"","recursive":false}}</tool>
        |<tool>{"name":"read_file","args":{"path":"notes/todo.md"}}</tool>
        |<tool>{"name":"write_file","args":{"path":"notes/todo.md","content":"..."}}</tool>
        |<tool>{"name":"append_file","args":{"path":"notes/log.txt","content":"..."}}</tool>
        |<tool>{"name":"create_pdf","args":{"path":"reports/summary.pdf","content":"..."}}</tool>
        |<tool>{"name":"create_folder","args":{"path":"notes"}}</tool>
        |<tool>{"name":"delete_path","args":{"path":"old/draft.txt"}}</tool>
        |
        |RULES:
        |1. To act, emit ONE OR MORE <tool>{...}</tool> blocks and NOTHING else in that reply.
        |2. After each tool block you will receive a TOOL_RESULTS message. Continue with more tools or finish.
        |3. When the task is done, reply with the final answer in clean markdown. NEVER include <tool> blocks in the final answer.
        |4. Prefer creating .md for notes, .pdf for documents/reports the user may print or share.
        |5. Always verify with list_files if unsure a file exists before overwriting.
        |6. Keep file content complete and well formatted; do not truncate.
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseToolBlocks(content: String): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        var idx = 0
        while (true) {
            val start = content.indexOf("<tool>", idx)
            if (start < 0) break
            val open = content.indexOf('{', start)
            // allow unclosed <tool> at end of message (truncated stream)
            val end = content.indexOf("</tool>", start).let { if (it < 0) content.length else it }
            if (open < 0 || open > end) { idx = start + 6; continue }
            // brace-match the JSON body
            var depth = 0; var jsonEnd = -1; var inStr = false; var esc = false
            for (i in open until end) {
                val ch = content[i]
                if (esc) { esc = false; continue }
                when {
                    ch == '\\' && inStr -> esc = true
                    ch == '"' -> inStr = !inStr
                    !inStr && ch == '{' -> depth++
                    !inStr && ch == '}' -> { depth--; if (depth == 0) { jsonEnd = i; break } }
                }
            }
            if (jsonEnd > open) {
                val body = content.substring(open, jsonEnd + 1)
                runCatching {
                    val obj = json.parseToJsonElement(body).jsonObject
                    val name = obj["name"]?.jsonPrimitive?.content
                    val args = obj["args"]?.jsonObject ?: obj["arguments"]?.jsonObject
                    if (name != null) calls += ToolCall(id = "", name = name, arguments = args?.toString() ?: "{}")
                }
                idx = jsonEnd + 1
            } else {
                idx = end
            }
        }
        return calls
    }

    fun stripToolBlocks(content: String): String =
        content.replace(Regex("<tool>[\\s\\S]*?</tool>"), "")
            .replace(Regex("<tool>[\\s\\S]*$"), "")
            .trim()

    fun stripPlan(content: String): String =
        content.replace(Regex("<plan>[\\s\\S]*?</plan>"), "").trim()

    fun contentOf(raw: JsonObject): String? = runCatching {
        raw["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.content
    }.getOrNull()
}
