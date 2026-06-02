package com.github.copilotmonitor.model

import java.time.Instant

data class SessionSummary(
    val id: String,
    val startTime: Instant,
    val endTime: Instant?,
    val totalInteractions: Int,
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalCacheReadTokens: Long,
    val avgAcceptanceRate: Double?,
    val modelsUsed: List<String>
)
