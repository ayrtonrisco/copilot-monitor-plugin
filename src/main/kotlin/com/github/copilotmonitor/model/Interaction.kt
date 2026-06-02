package com.github.copilotmonitor.model

import java.time.Instant

enum class FeatureType {
    COMPLETION_INLINE,
    CHAT_ASK,
    CHAT_EDIT,
    CHAT_AGENT,
    CHAT_PLAN,
    CLI_SESSION,
    CODE_REVIEW,
    UNKNOWN
}

enum class FinishReason { STOP, LENGTH, TOOL_CALLS, ERROR, UNKNOWN }

data class Interaction(
    val id: Long = 0,
    val timestamp: Instant,
    val model: String,
    val provider: String,
    val featureType: FeatureType,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val latencyMs: Long = -1,
    val ttftMs: Long = -1,
    val finishReason: FinishReason = FinishReason.UNKNOWN,
    val accepted: Boolean? = null,
    val sessionId: String = "",
    val isEstimated: Boolean = false
)
