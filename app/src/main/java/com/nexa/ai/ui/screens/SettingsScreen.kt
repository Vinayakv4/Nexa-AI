package com.nexa.ai.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.ai.data.prefs.Provider
import com.nexa.ai.vm.ChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val scope = rememberCoroutineScope()

    var providers by remember { mutableStateOf<List<Provider>>(emptyList()) }
    var activeId by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var temperature by remember { mutableStateOf(0.7f) }
    var workMode by remember { mutableStateOf(false) }
    var workspaceLabel by remember { mutableStateOf("") }
    var pickFolder by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<String?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            viewModel.setWorkspace(uri)
            workspaceLabel = uri.lastPathSegment?.substringAfterLast(':') ?: "folder"
        }
    }

    LaunchedEffect(pickFolder) {
        if (pickFolder) { folderPicker.launch(null); pickFolder = false }
    }

    LaunchedEffect(settings) {
        if (!loaded) {
            providers = settings.providers
            activeId = settings.activeProviderId
            systemPrompt = settings.systemPrompt
            temperature = settings.temperature
            workMode = settings.workModeEnabled
            if (settings.workspaceUri.isNotBlank()) {
                workspaceLabel = android.net.Uri.parse(settings.workspaceUri)
                    .lastPathSegment?.substringAfterLast(':') ?: "folder"
            }
            loaded = true
        }
    }

    fun update(p: Provider) {
        providers = providers.map { if (it.id == p.id) p else it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Section("AI Providers") {
                if (providers.isEmpty()) {
                    Text(
                        "Add an OpenAI-compatible provider (OpenAI, Groq, OpenRouter, Ollama…)",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                providers.forEach { p ->
                    ProviderCard(
                        provider = p,
                        active = p.id == activeId,
                        expanded = expandedId == p.id,
                        onToggleExpand = { expandedId = if (expandedId == p.id) null else p.id },
                        onSelect = { activeId = p.id },
                        onUpdate = ::update,
                        onDelete = {
                            providers = providers.filter { it.id != p.id }
                            if (activeId == p.id) activeId = providers.firstOrNull()?.id.orEmpty()
                        },
                        onTest = { onResult -> scope.launch { onResult(viewModel.testConnection(p)) } }
                    )
                }
                OutlinedButton(
                    onClick = {
                        val np = Provider(
                            id = "p_${System.currentTimeMillis()}",
                            name = "",
                            baseUrl = "",
                            apiKey = "",
                            models = emptyList()
                        )
                        providers = providers + np
                        activeId = activeId.ifBlank { np.id }
                        expandedId = np.id
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add provider")
                }
            }

            Section("System Prompt") {
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("Personality / instructions") },
                    minLines = 3,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Section("Creativity") {
                Text("Temperature: %.1f".format(temperature), fontSize = 13.sp)
                Slider(
                    value = temperature,
                    onValueChange = { temperature = (it * 10).toInt() / 10f },
                    valueRange = 0f..2f
                )
                Text(
                    "Lower = focused & factual, Higher = creative",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Section("🛠 Work Mode — agentic file access") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Let AI work in a folder", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "AI can create, read, edit & delete files, and generate PDFs in the folder you pick.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = workMode,
                        onCheckedChange = {
                            workMode = it
                            viewModel.setWorkMode(it)
                        }
                    )
                }
                if (workMode) {
                    Button(
                        onClick = { pickFolder = true },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (workspaceLabel.isNotBlank()) workspaceLabel else "Choose work folder…",
                            maxLines = 1
                        )
                    }
                }
            }

            if (workMode) {
                Section("⏱ Agent Tasks — autonomous & scheduled") {
                    TasksSection(viewModel)
                }
            }

            Button(
                onClick = {
                    saving = true
                    val cleaned = providers.mapNotNull { p ->
                        if (p.name.isBlank() || p.baseUrl.isBlank() || p.apiKey.isBlank() ||
                            p.models.all { it.isBlank() }) null
                        else p.copy(
                            baseUrl = p.baseUrl.trim().trimEnd('/'),
                            models = p.models.map { it.trim() }.filter { it.isNotBlank() },
                            activeModel = p.activeModel.ifBlank { p.models.first { it.isNotBlank() } }
                        )
                    }
                    val finalActive = if (cleaned.any { it.id == activeId }) activeId
                    else cleaned.firstOrNull()?.id.orEmpty()
                    viewModel.saveProviders(cleaned, finalActive, systemPrompt, temperature) {
                        saving = false
                        onBack()
                    }
                },
                enabled = !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Save Settings", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }

            Text(
                "Your keys never leave this device. Works with any [OI]-compatible API.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FooterMark()
        }
    }
}

@Composable
private fun FooterMark() {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 24.dp)
    ) {
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 18.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "N",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    "Nexa AI",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "v1.0 · local-first AI agent for Android",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Built by Vinayak",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/Vinayakv4")
                            )
                        )
                    }
                }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(
                Icons.Default.Code,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                "github.com/Vinayakv4",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) { content() }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: Provider,
    active: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelect: () -> Unit,
    onUpdate: (Provider) -> Unit,
    onDelete: () -> Unit,
    onTest: ((String) -> Unit) -> Unit
) {
    var showKey by remember { mutableStateOf(false) }
    var modelsText by remember(provider.id) { mutableStateOf(provider.models.joinToString("\n")) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    Surface(
        color = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onSelect)) {
                RadioButton(selected = active, onClick = onSelect)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        provider.name.ifBlank { "(unnamed provider)" },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Text(
                        provider.baseUrl.ifBlank { "no URL" },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Text(
                        provider.activeModel.ifBlank { "no model" },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
                if (testResult != null) {
                    Icon(
                        if (testResult == "OK") Icons.Default.Check else Icons.Default.Close,
                        contentDescription = null,
                        tint = if (testResult == "OK") Color(0xFF00A58A) else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onToggleExpand) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Edit"
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = provider.name,
                        onValueChange = { onUpdate(provider.copy(name = it)) },
                        label = { Text("Provider name") },
                        placeholder = { Text("OpenAI, Groq, OpenRouter, Ollama…") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = provider.baseUrl,
                        onValueChange = { onUpdate(provider.copy(baseUrl = it)) },
                        label = { Text("Base URL") },
                        placeholder = { Text("https://api.openai.com/v1") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = provider.apiKey,
                        onValueChange = { onUpdate(provider.copy(apiKey = it.trim())) },
                        label = { Text("API Key") },
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = modelsText,
                        onValueChange = {
                            modelsText = it
                            onUpdate(provider.copy(models = it.split('\n').map { m -> m.trim() }.filter { m -> m.isNotBlank() }))
                        },
                        label = { Text("Models (one per line)") },
                        placeholder = { Text("gpt-4o\nqwen3.8-max\nllama-3.3-70b") },
                        minLines = 2,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Active model: ${provider.activeModel.ifBlank { "first in list" }}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                testing = true
                                testResult = null
                                onTest { result ->
                                    testResult = result
                                    testing = false
                                }
                            },
                            enabled = !testing && provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (testing) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Test", fontSize = 12.sp)
                            }
                        }
                        TextButton(
                            onClick = onDelete,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Remove", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    testResult?.let {
                        Text(
                            if (it == "OK") "✓ Connection successful" else "✗ $it",
                            fontSize = 11.sp,
                            color = if (it == "OK") Color(0xFF00A58A) else MaterialTheme.colorScheme.error,
                            maxLines = 2
                        )
                    }
                }
            }
        }
    }
}
