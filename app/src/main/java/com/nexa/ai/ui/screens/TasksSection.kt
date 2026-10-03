package com.nexa.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.ai.data.db.AgentTaskEntity
import com.nexa.ai.vm.ChatViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TasksSection(viewModel: ChatViewModel) {
    val tasks by viewModel.agentTasks.collectAsState()
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var scheduleType by remember { mutableStateOf("once") }
    var hour by remember { mutableStateOf("9") }
    var minute by remember { mutableStateOf("0") }
    val timeFmt = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Task name") },
            placeholder = { Text("Morning news digest") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("What should the agent do?") },
            placeholder = { Text("Search the web for today's AI news and save a summary to notes/daily.md") },
            minLines = 2,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = scheduleType == "once",
                onClick = { scheduleType = "once" },
                label = { Text("Run now") }
            )
            FilterChip(
                selected = scheduleType == "daily",
                onClick = { scheduleType = "daily" },
                label = { Text("Daily at") }
            )
            if (scheduleType == "daily") {
                OutlinedTextField(
                    value = hour,
                    onValueChange = { hour = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text("HH") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.width(64.dp)
                )
                Text(":")
                OutlinedTextField(
                    value = minute,
                    onValueChange = { minute = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text("MM") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.width(64.dp)
                )
            }
        }
        Button(
            onClick = {
                if (title.isBlank() || prompt.isBlank()) return@Button
                val h = hour.toIntOrNull()?.coerceIn(0, 23) ?: 9
                val m = minute.toIntOrNull()?.coerceIn(0, 59) ?: 0
                viewModel.scheduleTask(
                    title.trim(), prompt.trim(),
                    if (scheduleType == "daily") "daily" else "once",
                    if (scheduleType == "daily") h else -1,
                    if (scheduleType == "daily") m else -1
                )
                title = ""; prompt = ""
            },
            enabled = title.isNotBlank() && prompt.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (scheduleType == "daily") "Schedule task" else "Queue task", fontWeight = FontWeight.SemiBold)
        }

        if (tasks.isNotEmpty()) {
            Text("TASKS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            tasks.take(8).forEach { t ->
                TaskRow(t, onRun = { viewModel.runQueuedTaskNow(t.id) }, onDelete = { viewModel.deleteTask(t.id) })
            }
        }
    }
}

@Composable
private fun TaskRow(task: AgentTaskEntity, onRun: () -> Unit, onDelete: () -> Unit) {
    val timeFmt = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
    val (statusLabel, color) = when (task.status) {
        "running" -> "Running" to MaterialTheme.colorScheme.primary
        "done" -> "Done" to MaterialTheme.colorScheme.tertiary
        "failed" -> "Failed" to MaterialTheme.colorScheme.error
        "cancelled" -> "Cancelled" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "Queued" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp)) {
            Icon(
                when (task.status) {
                    "running" -> Icons.Default.Pending
                    "done" -> Icons.Default.CheckCircle
                    "failed" -> Icons.Default.ErrorOutline
                    "cancelled" -> Icons.Default.Cancel
                    else -> Icons.Default.Schedule
                },
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(task.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    buildString {
                        append(if (task.scheduleType == "daily") "daily ${"%02d:%02d".format(task.scheduleHour, task.scheduleMinute)} • " else "")
                        append(statusLabel)
                        task.finishedAt?.let { append(" • ${timeFmt.format(Date(it))}") }
                    },
                    fontSize = 11.sp,
                    color = color
                )
            }
            if (task.status == "queued") {
                IconButton(onClick = onRun) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Run now", tint = MaterialTheme.colorScheme.primary)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    task.result.takeIf { it.isNotBlank() && task.status == "done" }?.let {
        Text(
            "↳ ${it.take(100)}",            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}
