package com.nexa.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.ai.agent.AgentLogBus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun levelColor(level: String): Color = when (level) {
    "ok" -> Color(0xFF4ADE80)
    "err" -> Color(0xFFFF5470)
    "tool" -> Color(0xFF60A5FA)
    "plan" -> Color(0xFFFBBF24)
    "think" -> Color(0xFFA78BFA)
    "out" -> Color(0xFFE2E8F0)
    else -> Color(0xFF94A3B8)
}

private fun levelTag(level: String): String = when (level) {
    "sys" -> "sys "
    "ok" -> " ok "
    "err" -> "err "
    "tool" -> "tool"
    "plan" -> "plan"
    "think" -> "thnk"
    "out" -> "out "
    else -> "    "
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val lines by AgentLogBus.lines.collectAsState()
    val listState = rememberLazyListState()
    val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("agent://console", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { AgentLogBus.clear() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0A0E1A),
                    titleContentColor = Color(0xFF4ADE80)
                )
            )
        },
        containerColor = Color(0xFF0A0E1A)
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(lines) { line ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        timeFmt.format(Date(line.ts)),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = Color(0xFF475569)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        levelTag(line.level),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = levelColor(line.level)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        line.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = levelColor(line.level).copy(alpha = 0.92f)
                    )
                }
            }
        }
    }
}
