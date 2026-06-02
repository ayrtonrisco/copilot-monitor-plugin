package com.github.copilotmonitor.model

data class CacheStats(
    val periodLabel: String,
    val cacheHitRate: Double,
    val cacheCreationRate: Double,
    val totalCacheReadTokens: Long,
    val totalCacheCreationTokens: Long,
    val estimatedSavingsUsd: Double,
    val recommendation: String?
)

data class DailyHitRate(
    val date: String,
    val hitRate: Double
)

data class CacheRecommendation(
    val priority: RecommendationPriority,
    val message: String,
    val action: String?
)

enum class RecommendationPriority { HIGH, MEDIUM, LOW, INFO }
