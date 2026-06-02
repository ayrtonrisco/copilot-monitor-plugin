package com.github.copilotmonitor.model

data class ContextWindowStatus(
    val usedTokensEstimate: Long,
    val maxPromptTokens: Long,
    val utilizationPct: Double,
    val openTabCount: Int,
    val activeFileSizeChars: Long,
    val selectionSizeChars: Long,
    val currentModel: String,
    val warning: ContextWindowWarning?
)

enum class ContextWindowWarning {
    APPROACHING_LIMIT,
    CRITICAL,
    EXCEEDED
}
