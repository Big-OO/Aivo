package com.assistant.aivo.core.models

import kotlinx.serialization.Serializable

@Serializable
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM;

    fun toApiRole(): String = when (this) {
        USER -> "user"
        ASSISTANT -> "assistant"
        SYSTEM -> "system"
    }
}

@Serializable
data class ChatMessage(
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
