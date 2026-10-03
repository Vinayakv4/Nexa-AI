package com.nexa.ai.agent

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.nexa.ai.data.files.Workspace
import com.nexa.ai.data.remote.ChatClient
import com.nexa.ai.data.remote.ChatEvent
import com.nexa.ai.data.remote.ChatMessage
import com.nexa.ai.data.remote.ChatRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

enum class AgentEventKind {
    STREAM_DELTA, PLAN_CREATED, TOOL_STARTED, TOOL_FINISHED,
    MEMORY_SAVED, FINAL_ANSWER, FAILED
}

sealed class AgentEvent {
    data class StreamDelta(val text: String) : AgentEvent()
    data class PlanCreated(val steps: List<String>) : AgentEvent()
    data class ToolStarted(val id: String, val name: String, val argsSummary: String) : AgentEvent()
    data class ToolFinished(val id: String, val name: String, val ok: Boolean, val output: String) : AgentEvent()
    data class MemorySaved(val preview: String) : AgentEvent()
    data class FinalAnswer(val text: String) : AgentEvent()
    data class Failed(val message: String) : AgentEvent()
}

data class AgentRunConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double,
    val workspaceUri: String,
    val userPrompt: String,
    val chatHistory: List<ChatMessage> = emptyList(),
    val maxSteps: Int = 12,
    val saveSnapshot: Boolean = true
)

class AgentEngine(private val context: Context, private val chatClient: ChatClient) {

    companion object {
        private const val MAX_STEPS = 12
        private const val RETRIES = 3
        const val MEMORY_FILE = "MEMORY.md"

        fun systemRules(): String = """
            |You are Nexa WORK MODE — an autonomous file agent with full access to the user's workspace folder.
            |
            |TOOLS (emit ONE OR MORE blocks per reply; NOTHING else in a tool-only reply):
            |<tool>{"name":"list_files","args":{"path":"","recursive":false}}</tool>
            |<tool>{"name":"read_file","args":{"path":"notes/todo.md"}}</tool>
            |<tool>{"name":"write_file","args":{"path":"notes/todo.md","content":"..."}}</tool>
            |<tool>{"name":"append_file","args":{"path":"notes/log.txt","content":"..."}}</tool>
            |<tool>{"name":"create_pdf","args":{"path":"reports/summary.pdf","content":"..."}}</tool>
            |<tool>{"name":"create_folder","args":{"path":"notes"}}</tool>
            |<tool>{"name":"delete_path","args":{"path":"old/draft.txt"}}</tool>
            |<tool>{"name":"web_search","args":{"query":"how do neural networks learn","max_results":5}}</tool>
            |<tool>{"name":"fetch_page","args":{"url":"https://example.com/article"}}</tool>
            |<tool>{"name":"write_memory","args":{"content":"full new MEMORY.md contents"}}</tool>
            |
            |PLANNING — only when it earns its keep:
            |- Small, obvious tasks (write one file, one search, a quick edit): skip the plan entirely. Just act.
            |- Multi-step work (researching + creating multiple files, reorganizing folders, reports): start your reply with a <plan> block of numbered steps, then your first tool blocks.
            |- Never produce a plan for a single-tool task.
            |RULES:
            |1. Tool-only replies: ONLY <tool> blocks, no prose.
            |2. You will receive TOOL_RESULTS messages; continue with more tools or finish.
            |3. Final answer: clean markdown, NO <tool> or <plan> tags.
            |4. MEMORY.md is your long-term memory. Use write_memory to persist important user preferences, project context, and lessons for future sessions.
            |5. Use web_search for facts you are unsure of; cite sources with URLs in final answers.
            |6. Verify file existence with list_files before overwriting. Keep file contents complete.
        """.trimIndent()
    }

    suspend fun run(config: AgentRunConfig, taskId: Long?): AgentEvent {
        val root = DocumentFile.fromTreeUri(context, android.net.Uri.parse(config.workspaceUri))
        if (root == null) return AgentEvent.Failed("Workspace folder is no longer accessible")

        if (config.saveSnapshot && taskId != null) {
            AgentLogBus.sys("snapshot: saving workspace state…")
            WorkspaceSnapshot.create(context, config.workspaceUri, taskId)
        }

        // Load memory
        val memory = Workspace.read(context, root, MEMORY_FILE)
        val memoryText = (memory as? Workspace.Result.Ok)?.message

        val messages = mutableListOf<ChatMessage>()
        messages += ChatMessage("system", systemRules())
        config.chatHistory.filter { it.role != "system" }.forEach { messages += it }
        messages += ChatMessage("user", config.userPrompt)
        memoryText?.let {
            messages += ChatMessage("system", "Your long-term memory (MEMORY.md, edit via write_memory):\n$it")
        }

        var planSteps: List<String>? = null
        var round = 0
        while (round < MAX_STEPS) {
            round++
            AgentLogBus.sys("── round $round ──")

            val content = streamWithRetry(config, compact(messages)) ?: run {
                val fail = "Model did not respond after $RETRIES attempts"
                AgentLogBus.err(fail)
                return AgentEvent.Failed(fail)
            }

            // Plan capture
            if (planSteps == null) {
                val p = parsePlan(content)
                if (p.isNotEmpty()) {
                    planSteps = p
                    AgentLogBus.plan("PLAN (${p.size} steps):\n" + p.mapIndexed { i, s -> "  ${i + 1}. $s" }.joinToString("\n"))
                    emit(AgentEvent.PlanCreated(p))
                }
            }

            val calls = AgentProtocol.parseToolBlocks(content)
            if (calls.isEmpty()) {
                val answer = AgentProtocol.stripPlan(AgentProtocol.stripToolBlocks(content))
                    .ifBlank { "(no response)" }
                AgentLogBus.ok("TASK COMPLETE")
                emit(AgentEvent.FinalAnswer(answer))
                return AgentEvent.FinalAnswer(answer)
            }

            messages += ChatMessage("assistant", content)

            // Parallel tool execution
            AgentLogBus.tool("executing ${calls.size} tool call(s) in parallel")
            val results: List<Pair<ToolCall, ToolResult>> = coroutineScope {
                calls.map { call ->
                    async(Dispatchers.IO) {
                        emit(AgentEvent.ToolStarted(call.name, call.name, call.summary()))
                        AgentLogBus.tool("→ ${call.name} ${call.summary()}")
                        val r = Tools.execute(context, config.workspaceUri, call)
                        val ok = !r.output.startsWith("ERROR")
                        if (ok) {
                            AgentLogBus.ok("✓ ${call.name} ${call.summary()} :: ${r.output.take(100)}")
                            if (call.name == "write_memory") {
                                emit(AgentEvent.MemorySaved(r.output))
                            }
                        } else {
                            AgentLogBus.err("✗ ${call.name} ${call.summary()} :: ${r.output.take(100)}")
                        }
                        emit(AgentEvent.ToolFinished(call.name, call.name, ok, r.output.take(200)))
                        call to r
                    }
                }.awaitAll()
            }

            val resultsText = results.joinToString("\n") { (call, res) -> "[${call.name}] ${res.output}" }
            messages += ChatMessage("user", "TOOL_RESULTS:\n$resultsText\n\nContinue: more tool blocks, or your final answer.")
        }
        val msg = "Reached $MAX_STEPS step limit — ask to continue."
        AgentLogBus.err(msg)
        return AgentEvent.Failed(msg)
    }

    private suspend fun emit(e: AgentEvent) { AgentEvents.tryEmit(e) }

    private suspend fun streamWithRetry(config: AgentRunConfig, messages: List<ChatMessage>): String? {
        for (attempt in 1..RETRIES) {
            val sb = StringBuilder()
            var gotDelta = false
            var hardFail: String? = null
            val request = ChatRequest(
                model = config.model,
                messages = messages,
                temperature = config.temperature,
                stream = true
            )
            chatClient.stream(config.baseUrl, config.apiKey, request).collect { ev ->
                when (ev) {
                    is ChatEvent.Delta -> { gotDelta = true; sb.append(ev.text); emit(AgentEvent.StreamDelta(ev.text)) }
                    is ChatEvent.Done -> { /* end */ }
                    is ChatEvent.Failure -> if (!gotDelta) hardFail = ev.message
                }
            }
            if (sb.isNotBlank()) return sb.toString()
            val reason = hardFail ?: "empty response"
            AgentLogBus.err("attempt $attempt failed: $reason")
            if (attempt < RETRIES) {
                val backoff = 1000L * (1 shl (attempt - 1))
                AgentLogBus.sys("retrying in ${backoff}ms…")
                delay(backoff)
            }
        }
        return null
    }

    // Keep system prompts + first user msg; trim older tool-result rounds
    fun compact(messages: List<ChatMessage>): List<ChatMessage> {
        val firstUserIdx = messages.indexOfFirst { it.role == "user" }
        val lastTwoTool = messages.mapIndexedNotNull { i, m -> if (m.content?.startsWith("TOOL_RESULTS:") == true) i else null }
            .takeLast(2).toSet()
        return messages.mapIndexed { i, m ->
            val isSystem = m.role == "system"
            val isFirstUser = i == firstUserIdx
            val keepFull = isSystem || isFirstUser || lastTwoTool.contains(i) || m.role == "assistant"
            if (keepFull) m
            else m.copy(content = (m.content ?: "").take(400) + "\n…(older context trimmed)")
        }
    }

    fun parsePlan(content: String): List<String> {
        val regex = Regex("<plan>([\\s\\S]*?)</plan>")
        val body = regex.find(content)?.groupValues?.get(1) ?: return emptyList()
        return body.lines()
            .map { it.trim().removePrefix("-").trim() }
            .filter { it.isNotEmpty() }
            .map { it.replace(Regex("^\\d+[.)]\\s*"), "") }
            .filter { it.isNotEmpty() }
    }
}
