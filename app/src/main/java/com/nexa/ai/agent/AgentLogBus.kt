package com.nexa.ai.agent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConsoleLine(
    val ts: Long = System.currentTimeMillis(),
    val level: String,   // "sys" | "in" | "out" | "tool" | "ok" | "err" | "plan" | "think"
    val text: String
)

object AgentLogBus {
    private val _lines = MutableStateFlow<List<ConsoleLine>>(emptyList())
    val lines: StateFlow<List<ConsoleLine>> = _lines.asStateFlow()

    private val _events = MutableSharedFlow<ConsoleLine>(extraBufferCapacity = 256)
    val events: SharedFlow<ConsoleLine> = _events.asSharedFlow()

    private const val MAX_LINES = 800

    fun log(level: String, text: String) {
        val line = ConsoleLine(level = level, text = text)
        _lines.value = (_lines.value + line).takeLast(MAX_LINES)
        _events.tryEmit(line)
    }

    fun sys(t: String) = log("sys", t)
    fun plan(t: String) = log("plan", t)
    fun think(t: String) = log("think", t)
    fun tool(t: String) = log("tool", t)
    fun ok(t: String) = log("ok", t)
    fun err(t: String) = log("err", t)
    fun out(t: String) = log("out", t)

    fun clear() {
        _lines.value = emptyList()
    }
}
