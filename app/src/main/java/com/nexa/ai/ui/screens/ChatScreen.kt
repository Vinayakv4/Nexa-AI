package com.nexa.ai.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HomeRepairService
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import com.nexa.ai.data.db.MessageEntity
import com.nexa.ai.ui.components.MarkdownText
import com.nexa.ai.vm.Attachment
import com.nexa.ai.vm.ChatViewModel
import kotlinx.coroutines.launch

private val SUGGESTIONS = listOf(
    "Summarize this PDF for me",
    "Explain a concept simply",
    "Make study notes",
    "Quiz me on a topic"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSidebar: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val ui by viewModel.ui.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    var input by remember { mutableStateOf("") }
    var pickTrigger by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    DocumentPickerEffect(viewModel, pickTrigger)

    val displayItems = remember(ui.messages, ui.isStreaming, ui.streamingText, ui.isWorking) {
        buildList {
            addAll(ui.messages)
            if (ui.isStreaming) add(
                MessageEntity(id = -1, conversationId = -1, role = "assistant", content = ui.streamingText, timestamp = 0)
            )
            if (ui.isWorking && ui.agentSteps.isEmpty() && ui.plan.isEmpty()) add(
                MessageEntity(id = -2, conversationId = -1, role = "assistant", content = "", timestamp = 0)
            )
        }
    }

    val atBottom by remember {
        derivedStateOf { !listState.canScrollForward || listState.isScrolledToLast(displayItems.size) }
    }

    LaunchedEffect(displayItems.size) {
        if (displayItems.isNotEmpty()) listState.scrollToItem(displayItems.lastIndex)
    }
    LaunchedEffect(ui.streamingText.length) {
        if (ui.isStreaming && atBottom && displayItems.isNotEmpty()) {
            listState.scrollToItem(displayItems.lastIndex)
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .imePadding()
            ) {
                var modelSheet by remember { mutableStateOf(false) }

                if (modelSheet) {
                    ModelSheet(
                        viewModel = viewModel,
                        onDismiss = { modelSheet = false }
                    )
                }

                ChatTopBar(
                    title = conversations.firstOrNull { it.id == ui.conversationId }?.title,
                    model = settings.model.ifBlank { "No model" },
                    providerName = settings.providerName,
                    workMode = settings.workModeEnabled && settings.hasWorkspace,
                    onMenu = onOpenSidebar,
                    onSettings = onOpenSettings,
                    onNewChat = { viewModel.newChat() },
                    onModelTap = { modelSheet = true }
                )

                if (displayItems.isEmpty()) {
                    EmptyState(
                        model = settings.model,
                        modifier = Modifier.weight(1f),
                        onSuggestion = { input = it }
                    )
                } else {
                    Box(modifier = Modifier.weight(1f)) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(displayItems, key = { it.id }) { message ->
                                when {
                                    message.id == -2L -> ThinkingIndicator()
                                    else -> MessageBubble(
                                        message = message,
                                        isStreaming = ui.isStreaming && message.id == -1L
                                    )
                                }
                            }
                        }

                        ScrollToBottomButton(
                            visible = !atBottom,
                            onClick = {
                                scope.launch {
                                    if (displayItems.isNotEmpty()) {
                                        listState.animateScrollToItem(displayItems.lastIndex)
                                    }
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp)
                        )
                    }
                }

                AnimatedVisibility(visible = ui.isWorking || (ui.isStreaming && ui.plan.isNotEmpty())) {
                    Column {
                        if (ui.plan.isNotEmpty()) {
                            PlanChecklist(plan = ui.plan)
                        }
                        if (ui.isWorking && ui.agentSteps.isNotEmpty()) {
                            AgentStepsPanel(steps = ui.agentSteps.takeLast(4))
                        }
                    }
                }

                AnimatedVisibility(visible = ui.lastTaskId != null && ui.lastTaskOk && !ui.isWorking && !ui.isStreaming) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Text(
                                "Task completed — workspace changed?",
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.undoLastTask() }) {
                                Text("↩ Undo", fontSize = 12.sp)
                            }
                            IconButton(onClick = { viewModel.dismissUndo() }) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = ui.error != null) {
                    ui.error?.let { err ->
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 14.dp)
                            ) {
                                Text(
                                    err,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { viewModel.dismissError() }) {
                                    Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = ui.attachment != null || ui.isExtracting) {
                    AttachmentBar(
                        attachment = ui.attachment,
                        isExtracting = ui.isExtracting,
                        onRemove = { viewModel.removeAttachment() }
                    )
                }

                Composer(
                    input = input,
                    onInputChange = { input = it },
                    isStreaming = ui.isStreaming,
                    isWorking = ui.isWorking,
                    workMode = settings.workModeEnabled && settings.hasWorkspace,
                    canSend = (input.isNotBlank() || ui.attachment != null) && !ui.isStreaming && !ui.isWorking,
                    onSend = {
                        viewModel.sendMessage(input)
                        input = ""
                    },
                    onStop = { viewModel.stopStreaming() },
                    onPickFile = { pickTrigger++ }
                )
            }
        }
    }
}

@Composable
private fun PlanChecklist(plan: List<com.nexa.ai.vm.PlanStepUi>) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Checklist,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "PLAN",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(4.dp))
            plan.forEachIndexed { i, step ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                    Icon(
                        if (step.done) Icons.Default.CheckCircle else Icons.Default.Radio,
                        contentDescription = null,
                        tint = if (step.done) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${i + 1}. ${step.text}",
                        fontSize = 12.sp,
                        maxLines = 1,
                        color = if (step.done) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentStepsPanel(steps: List<com.nexa.ai.vm.AgentStep>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text(
                    "Working in your folder…",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(6.dp))
            steps.forEach { step ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 1.dp)
                ) {
                    when (step.ok) {
                        null -> CircularProgressIndicator(
                            modifier = Modifier.size(10.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        true -> Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(12.dp)
                        )
                        false -> Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${step.label}  ${step.detail.take(55)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun ScrollToBottomButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.6f),
        exit = fadeOut() + scaleOut(targetScale = 0.6f),
        modifier = modifier
    ) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(40.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll to bottom")
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListState.isScrolledToLast(count: Int): Boolean {
    if (count == 0) return true
    val lastInfo = layoutInfo.visibleItemsInfo.lastOrNull() ?: return true
    return lastInfo.index == count - 1 &&
        lastInfo.offset + lastInfo.size <= layoutInfo.viewportEndOffset + 80
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    title: String?,
    model: String,
    providerName: String,
    workMode: Boolean,
    onMenu: () -> Unit,
    onSettings: () -> Unit,
    onNewChat: () -> Unit,
    onModelTap: () -> Unit
) {
    CenterAlignedTopAppBar(
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    title ?: "Nexa",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Surface(
                    color = if (workMode) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable(onClick = onModelTap)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        if (workMode) {
                            Icon(
                                Icons.Default.HomeRepairService,
                                contentDescription = null,
                                tint = if (workMode) MaterialTheme.colorScheme.onTertiaryContainer
                                else MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(Modifier.size(3.dp))
                        }
                        Text(
                            buildString {
                                if (workMode) append("WORK • ")
                                if (providerName.isNotBlank()) { append(providerName); append(" / ") }
                                append(model)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (workMode) MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1
                        )
                    }
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onMenu) {
                Icon(Icons.Default.Menu, contentDescription = "Chats")
            }
        },
        actions = {
            IconButton(onClick = onNewChat) {
                Icon(Icons.Default.Add, contentDescription = "New chat")
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        )
    )
}

@Composable
private fun EmptyState(
    model: String,
    modifier: Modifier = Modifier,
    onSuggestion: (String) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(38.dp)
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Nexa AI",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Ask anything, upload PDFs & notes, get\nformatted answers — all stored locally.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            model,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(24.dp))
        SUGGESTIONS.forEach { suggestion ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .clickable { onSuggestion(suggestion) }
            ) {
                Text(
                    suggestion,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: MessageEntity, isStreaming: Boolean) {
    val isUser = message.role == "user"
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (isUser) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = 18.dp,
                    bottomEnd = 6.dp
                ),
                modifier = Modifier.widthIn(max = 310.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (!message.attachmentName.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                message.attachmentName,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    SelectionContainer {
                        Text(message.content, fontSize = 15.sp, color = Color.White)
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!message.attachmentName.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                message.attachmentName,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (message.content.isNotBlank()) {
                    SelectionContainer {
                        MarkdownText(markdown = message.content)
                    }
                }
                when {
                    isStreaming && message.content.isBlank() -> TypingIndicator()
                    isStreaming -> BlinkingCursor()
                    message.content.isNotBlank() -> {
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(message.content)) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Copy",
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val transition = rememberInfiniteTransition(label = "thinking")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 3f,
            animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart),
            label = "phase"
        )
        repeat(3) { i ->
            val active = (phase.toInt() % 3) == i
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (active) 9.dp else 6.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = if (active) 1f else 0.3f),
                        RoundedCornerShape(50)
                    )
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "Thinking…",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    val alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "typingAlpha"
    )
    Text("• • •", color = MaterialTheme.colorScheme.primary.copy(alpha = alpha), fontSize = 16.sp)
}

@Composable
private fun BlinkingCursor() {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "cursorAlpha"
    )
    Text("▍", color = MaterialTheme.colorScheme.primary.copy(alpha = alpha))
}

@Composable
private fun AttachmentBar(attachment: Attachment?, isExtracting: Boolean, onRemove: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        if (isExtracting) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
                Text("Reading document…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (attachment != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp)
            ) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    attachment.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove attachment",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(
    input: String,
    onInputChange: (String) -> Unit,
    isStreaming: Boolean,
    isWorking: Boolean,
    workMode: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickFile: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp)) {
            if (workMode) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.HomeRepairService,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "Work Mode — I can read & write files in your folder",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 2.dp,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                )
            ) {
                Column {
                    OutlinedTextField(
                        value = input,
                        onValueChange = onInputChange,
                        placeholder = { Text("Ask anything…", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            cursorColor = MaterialTheme.colorScheme.primary
                        ),
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() })
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 10.dp, bottom = 8.dp)
                    ) {
                        IconButton(onClick = onPickFile, modifier = Modifier.size(38.dp)) {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = "Attach PDF or text file",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (isStreaming || isWorking) {
                            FilledIconButton(
                                onClick = onStop,
                                modifier = Modifier.size(42.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                )
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(20.dp))
                            }
                        } else {
                            FilledIconButton(
                                onClick = onSend,
                                enabled = canSend,
                                modifier = Modifier.size(42.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(19.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
