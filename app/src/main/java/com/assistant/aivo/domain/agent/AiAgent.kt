package com.assistant.aivo.domain.agent

import com.assistant.aivo.core.Result
import com.assistant.aivo.core.models.ChatMessage
import androidx.appfunctions.metadata.AppFunctionMetadata
import kotlinx.serialization.json.JsonElement

interface AiAgent {
    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<AppFunctionMetadata> = emptyList(),
        onToolCallProgress: (String) -> Unit = {},
        executeTool: suspend (packageName: String, functionName: String, args: Map<String, JsonElement>) -> String = { _, _, _ -> "" }
    ): Result<ChatMessage>
}
