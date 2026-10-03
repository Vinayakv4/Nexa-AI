package com.nexa.ai.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.ai.agent.AgentProtocol
import com.nexa.ai.agent.Tools
import com.nexa.ai.agent.AgentEngine
import com.nexa.ai.agent.AgentEvent
import com.nexa.ai.agent.AgentEvents
import com.nexa.ai.agent.AgentLogBus
import com.nexa.ai.agent.AgentRunConfig
import com.nexa.ai.agent.AgentScheduler
import com.nexa.ai.agent.WorkspaceSnapshot
import com.nexa.ai.data.db.AppDatabase
import com.nexa.ai.data.db.ConversationEntity
import com.nexa.ai.data.db.MessageEntity
import com.nexa.ai.data.files.DocumentExtractor
import com.nexa.ai.data.prefs.AppSettings
import com.nexa.ai.data.prefs.Provider
import com.nexa.ai.data.prefs.SettingsRepository
import com.nexa.ai.data.remote.ChatClient
import com.nexa.ai.data.remote.ChatEvent
import com.nexa.ai.data.remote.ChatMessage
import com.nexa.ai.data.remote.ChatRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class Attachment(
    val name: String,
    val text: String
)

data class AgentStep(val label: String, val detail: String, val ok: Boolean? = null)

data class PlanStepUi(val text: String, val done: Boolean = false)

data class ConversationUiState(
    val conversationId: Long? = null,
    val messages: List<MessageEntity> = emptyList(),
    val streamingText: String = "",
    val isStreaming: Boolean = false,
    val isWorking: Boolean = false,
    val agentSteps: List<AgentStep> = emptyList(),
    val plan: List<PlanStepUi> = emptyList(),
    val activeTaskId: Long? = null,
    val lastTaskId: Long? = null,
    val lastTaskOk: Boolean = false,
    val error: String? = null,
    val attachment: Attachment? = null,
    val isExtracting: Boolean = false
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val settingsRepo = SettingsRepository(app)
    private val chatClient = ChatClient()

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val conversations = db.conversationDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _ui = MutableStateFlow(ConversationUiState())
    val ui: StateFlow<ConversationUiState> = _ui.asStateFlow()

    private var streamJob: Job? = null
    private var messagesJob: Job? = null

    private fun observeMessages(convId: Long) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            db.messageDao().observeForConversation(convId).collect { messages ->
                _ui.value = _ui.value.copy(messages = messages)
            }
        }
    }

    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        persistStreamedText()
    }

    private fun persistStreamedText() {
        val state = _ui.value
        val convId = state.conversationId
        if (state.isStreaming && convId != null && state.streamingText.isNotBlank()) {
            viewModelScope.launch {
                db.messageDao().insert(
                    MessageEntity(
                        conversationId = convId,
                        role = "assistant",
                        content = state.streamingText,
                        timestamp = System.currentTimeMillis()
                    )
                )
                db.conversationDao().touch(convId, System.currentTimeMillis())
            }
        }
        _ui.value = state.copy(isStreaming = false, streamingText = "")
    }

    fun newChat() {
        stopStreaming()
        _ui.value = ConversationUiState()
    }

    fun dismissError() {
        _ui.value = _ui.value.copy(error = null)
    }

    fun dismissUndo() {
        _ui.value = _ui.value.copy(lastTaskId = null)
    }

    fun selectConversation(id: Long) {
        if (_ui.value.conversationId == id && !_ui.value.isStreaming) return
        stopStreaming()
        _ui.value = ConversationUiState(conversationId = id)
        observeMessages(id)
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            if (_ui.value.conversationId == id) newChat()
            db.messageDao().deleteForConversation(id)
            db.conversationDao().delete(id)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            newChat()
            db.messageDao().apply { conversations.value.forEach { deleteForConversation(it.id) } }
            db.conversationDao().deleteAll()
        }
    }

    fun setWorkMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setWorkMode(enabled) }
    }

    fun setWorkspace(uri: Uri?) {
        viewModelScope.launch {
            if (uri != null) {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            settingsRepo.setWorkspace(uri?.toString() ?: "")
        }
    }

    fun saveProviders(providers: List<Provider>, activeId: String, systemPrompt: String, temperature: Float, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsRepo.saveProviders(providers, activeId)
            settingsRepo.setSystemPromptAndTemp(systemPrompt, temperature)
            onSaved()
        }
    }

    fun setActiveProvider(id: String) {
        viewModelScope.launch { settingsRepo.setActiveProvider(id) }
    }

    fun setModel(model: String) {
        val current = settings.value
        val active = current.active ?: return
        val updated = active.copy(activeModel = model)
        val providers = current.providers.map { if (it.id == active.id) updated else it }
        viewModelScope.launch {
            settingsRepo.saveProviders(providers, current.activeProviderId)
            db.conversationDao().updateModel(
                _ui.value.conversationId ?: return@launch,
                model,
                System.currentTimeMillis()
            )
        }
    }

    suspend fun testConnection(provider: Provider): String {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val request = ChatRequest(
                model = provider.activeModel.ifBlank { provider.models.firstOrNull() ?: "gpt-4o-mini" },
                messages = listOf(ChatMessage("user", "ping")),
                temperature = 0.0,
                stream = false
            )
            when (val r = chatClient.complete(provider.baseUrl.trimEnd('/'), provider.apiKey, request)) {
                is ChatClient.CompleteResult.Success -> "OK"
                is ChatClient.CompleteResult.Failed -> r.message
            }
        }
    }

    fun removeAttachment() {
        _ui.value = _ui.value.copy(attachment = null)
    }

    fun loadDocument(uri: Uri) {
        if (_ui.value.isExtracting) return
        _ui.value = _ui.value.copy(isExtracting = true, error = null)
        viewModelScope.launch {
            val result = DocumentExtractor.extract(getApplication(), uri)
            _ui.value = if (result != null) {
                _ui.value.copy(isExtracting = false, attachment = Attachment(result.first, result.second))
            } else {
                _ui.value.copy(isExtracting = false, error = "Could not read that file. Supported: PDF, TXT, MD, CSV.")
            }
        }
    }

    fun sendMessage(userText: String) {
        val text = userText.trim()
        val attachment = _ui.value.attachment
        if (text.isEmpty() && attachment == null) return
        if (_ui.value.isStreaming) return

        viewModelScope.launch {
            val s = settings.value
            if (!s.isConfigured) {
                _ui.value = _ui.value.copy(error = "Add your API key in Settings first.")
                return@launch
            }

            var convId = _ui.value.conversationId
            if (convId == null) {
                convId = db.conversationDao().insert(
                    ConversationEntity(
                        title = attachment?.name ?: text.take(42),
                        model = s.model,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                )
                _ui.value = _ui.value.copy(conversationId = convId)
                observeMessages(convId)
            } else {
                db.conversationDao().touch(convId, System.currentTimeMillis())
            }

            val history = db.messageDao().observeForConversation(convId).first()
            val isFirstMessage = history.none { it.role == "user" }

            val userMessage = MessageEntity(
                conversationId = convId,
                role = "user",
                content = text,
                attachmentName = attachment?.name,
                attachmentText = attachment?.text,
                timestamp = System.currentTimeMillis()
            )
            db.messageDao().insert(userMessage)
            if (isFirstMessage) {
                db.conversationDao().rename(convId, attachment?.name ?: text.take(42), System.currentTimeMillis())
            }
            _ui.value = _ui.value.copy(attachment = null, error = null, isStreaming = true, streamingText = "")

            val apiMessages = buildApiMessages(s, history + userMessage)
            if (s.workModeEnabled && s.hasWorkspace) {
                runWorkTask(convId, s, apiMessages, text.ifBlank { "Work with the attached document: ${attachment?.name}" })
            } else if (s.workModeEnabled && !s.hasWorkspace) {
                _ui.value = _ui.value.copy(isStreaming = false, error = "Pick a work folder first (Settings → Work Mode).")
            } else {
                startStream(convId, s, apiMessages)
            }
        }
    }

    private fun runWorkTask(convId: Long, s: AppSettings, base: List<ChatMessage>, userText: String) {
        streamJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(
                isStreaming = false, isWorking = true, agentSteps = emptyList(),
                streamingText = "", plan = emptyList(), lastTaskId = null, lastTaskOk = false
            )
            val rawStream = StringBuilder()
            // 1. Create task row (enables undo + console + notifications)
            val taskId = db.agentTaskDao().insert(
                com.nexa.ai.data.db.AgentTaskEntity(
                    title = userText.take(60),
                    prompt = userText,
                    scheduleType = "chat",
                    scheduleHour = -1,
                    scheduleMinute = -1,
                    status = "running",
                    createdAt = System.currentTimeMillis(),
                    startedAt = System.currentTimeMillis()
                )
            )
            _ui.value = _ui.value.copy(activeTaskId = taskId)
            AgentLogBus.sys("task #$taskId in-chat: ${userText.take(60)}")

            val engine = AgentEngine(getApplication(), chatClient)
            val config = AgentRunConfig(
                baseUrl = s.baseUrl,
                apiKey = s.apiKey,
                model = s.model,
                temperature = s.temperature.toDouble(),
                workspaceUri = s.workspaceUri,
                userPrompt = userText,
                chatHistory = base
            )

            val collector = launch {
                AgentEvents.flow.collect { ev ->
                    when (ev) {
                        is AgentEvent.StreamDelta -> {
                            rawStream.append(ev.text)
                            _ui.value = _ui.value.copy(
                                isWorking = false, isStreaming = true,
                                streamingText = sanitizeAgentStream(rawStream)
                            )
                        }
                        is AgentEvent.PlanCreated -> {
                            _ui.value = _ui.value.copy(isStreaming = false, isWorking = true, plan = ev.steps.map { PlanStepUi(it) })
                        }
                        is AgentEvent.ToolStarted -> {
                            addStep(AgentStep(ev.name, ev.argsSummary, null))
                            markPlanStep()
                        }
                        is AgentEvent.ToolFinished -> {
                            finishStep(ev.name, ev.ok, ev.output.take(120))
                        }
                        is AgentEvent.MemorySaved -> addStep(AgentStep("memory", "MEMORY.md updated", true))
                        is AgentEvent.FinalAnswer -> { /* handled below */ }
                        is AgentEvent.Failed -> { /* handled below */ }
                    }
                }
            }

            try {
                val result = engine.run(config, taskId)
                collector.cancel()
                when (result) {
                    is AgentEvent.FinalAnswer -> {
                        db.messageDao().insert(
                            MessageEntity(
                                conversationId = convId,
                                role = "assistant",
                                content = result.text,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                        db.conversationDao().touch(convId, System.currentTimeMillis())
                        db.agentTaskDao().finish(taskId, "done", result.text, System.currentTimeMillis())
                        _ui.value = _ui.value.copy(
                            isStreaming = false, isWorking = false, streamingText = "",
                            lastTaskId = taskId, lastTaskOk = true
                        )
                    }
                    is AgentEvent.Failed -> {
                        _ui.value = _ui.value.copy(
                            isStreaming = false, isWorking = false, streamingText = "",
                            error = result.message, lastTaskId = taskId, lastTaskOk = false
                        )
                        db.agentTaskDao().finish(taskId, "failed", result.message, System.currentTimeMillis())
                    }
                    else -> {}
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                collector.cancel()
                db.agentTaskDao().finish(taskId, "cancelled", "stopped by user", System.currentTimeMillis())
                _ui.value = _ui.value.copy(isStreaming = false, isWorking = false, streamingText = "")
                throw e
            } catch (e: Exception) {
                collector.cancel()
                _ui.value = _ui.value.copy(
                    isStreaming = false, isWorking = false, streamingText = "",
                    error = e.message ?: "Work task failed", lastTaskId = taskId, lastTaskOk = false
                )
                db.agentTaskDao().finish(taskId, "failed", e.message ?: "failed", System.currentTimeMillis())
            } finally {
                _ui.value = _ui.value.copy(agentSteps = emptyList(), activeTaskId = null)
            }
        }
    }

    private fun markPlanStep() {
        val plan = _ui.value.plan
        val idx = plan.indexOfFirst { !it.done }
        if (idx >= 0) {
            _ui.value = _ui.value.copy(plan = plan.mapIndexed { i, s -> if (i == idx) s.copy(done = true) else s })
        }
    }

    fun undoLastTask() {
        val s = settings.value
        val taskId = _ui.value.lastTaskId ?: return
        viewModelScope.launch {
            val (restored, deleted) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                WorkspaceSnapshot.undo(getApplication(), s.workspaceUri, taskId)
            }
            _ui.value = _ui.value.copy(
                lastTaskId = null, lastTaskOk = false,
                error = null
            )
            AgentLogBus.ok("undo: $restored restored, $deleted deleted")
            db.messageDao().insert(
                MessageEntity(
                    conversationId = _ui.value.conversationId ?: return@launch,
                    role = "assistant",
                    content = "↩️ **Undo complete** — restored $restored file(s), removed $deleted file(s) created by the last task.",
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun scheduleTask(title: String, prompt: String, scheduleType: String, hour: Int, minute: Int) {
        viewModelScope.launch {
            db.agentTaskDao().insert(
                com.nexa.ai.data.db.AgentTaskEntity(
                    title = title.take(60),
                    prompt = prompt,
                    scheduleType = scheduleType,
                    scheduleHour = hour,
                    scheduleMinute = minute,
                    status = "queued",
                    createdAt = System.currentTimeMillis()
                )
            )
            AgentScheduler.ensurePeriodicTick(getApplication())
            AgentScheduler.kickNow(getApplication())
        }
    }

    val agentTasks = db.agentTaskDao().observeScheduled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun runQueuedTaskNow(taskId: Long) {
        AgentScheduler.kickNow(getApplication())
    }

    fun deleteTask(taskId: Long) {
        viewModelScope.launch { db.agentTaskDao().delete(taskId) }
    }

    init {
        viewModelScope.launch { db.agentTaskDao().purgeChatRuns() }
    }

    private fun addStep(step: AgentStep) {
        _ui.value = _ui.value.copy(agentSteps = _ui.value.agentSteps + step)
    }

    private fun finishStep(name: String, ok: Boolean, detail: String) {
        val steps = _ui.value.agentSteps.toMutableList()
        val idx = steps.indexOfLast { it.label == name && it.ok == null }
        if (idx >= 0) steps[idx] = steps[idx].copy(ok = ok, detail = detail)
        else steps += AgentStep(name, detail, ok)
        _ui.value = _ui.value.copy(agentSteps = steps)
    }

    /**
     * Streaming sanitizer: hides plan/tool blocks until a block is COMPLETE
     * (so raw tags never flash in chat mid-stream).
     */
    private fun sanitizeAgentStream(raw: StringBuilder): String {
        var text = raw.toString()
        text = text.replace(Regex("<plan>[\\s\\S]*?</plan>"), "")
        text = text.replace(Regex("<tool>[\\s\\S]*?</tool>"), "")
        val openPlan = text.lastIndexOf("<plan>")
        if (openPlan >= 0) text = text.substring(0, openPlan)
        val openTool = text.lastIndexOf("<tool>")
        if (openTool >= 0) text = text.substring(0, openTool)
        return text.trim()
    }

    private fun buildApiMessages(
        s: AppSettings,
        history: List<MessageEntity>
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        if (s.systemPrompt.isNotBlank()) messages += ChatMessage("system", s.systemPrompt)
        for (m in history) {
            val content = buildString {
                append(m.content)
                if (!m.attachmentText.isNullOrBlank()) {
                    append("\n\n[Attached document: ")
                    append(m.attachmentName)
                    append("]\n")
                    append(m.attachmentText)
                }
            }
            messages += ChatMessage(m.role, content)
        }
        return messages
    }

    private fun startStream(convId: Long, s: AppSettings, apiMessages: List<ChatMessage>) {
        streamJob = viewModelScope.launch {
            val request = ChatRequest(
                model = s.model,
                messages = apiMessages,
                temperature = s.temperature.toDouble()
            )
            chatClient.stream(s.baseUrlSafe(), s.apiKey, request).collect { event ->
                when (event) {
                    is ChatEvent.Delta -> {
                        _ui.value = _ui.value.copy(streamingText = _ui.value.streamingText + event.text)
                    }
                is ChatEvent.Failure -> {
                    persistPartial(convId)
                    _ui.value = _ui.value.copy(isStreaming = false, streamingText = "", error = event.message)
                }
                    is ChatEvent.Done -> {
                        persistPartial(convId)
                        _ui.value = _ui.value.copy(isStreaming = false, streamingText = "")
                    }
                }
            }
        }
    }

    private fun persistPartial(convId: Long) {
        val partial = _ui.value.streamingText
        if (partial.isNotBlank()) {
            viewModelScope.launch {
                db.messageDao().insert(
                    MessageEntity(
                        conversationId = convId,
                        role = "assistant",
                        content = partial,
                        timestamp = System.currentTimeMillis()
                    )
                )
                db.conversationDao().touch(convId, System.currentTimeMillis())
            }
        }
    }

    private fun AppSettings.baseUrlSafe() = baseUrl
}
