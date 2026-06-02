package com.github.copilotmonitor.model

data class DailySummary(
    val date: String,
    val model: String,
    val featureType: FeatureType,
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalCacheReadTokens: Long,
    val cacheHitRate: Double,
    val acceptanceRate: Double?,
    val avgLatencyMs: Double,
    val avgTtftMs: Double,
    val costEstimateUsd: Double,
    val interactionCount: Int,
    val errorCount: Int
)
