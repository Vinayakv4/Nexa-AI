package com.nexa.ai.agent

import kotlinx.coroutines.flow.MutableSharedFlow

object AgentEvents {
    val flow = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 512)
    suspend fun tryEmit(e: AgentEvent) { flow.tryEmit(e) }
}
