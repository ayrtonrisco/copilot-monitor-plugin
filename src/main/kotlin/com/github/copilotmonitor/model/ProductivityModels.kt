package com.github.copilotmonitor.model

data class CodeGenerationStats(
    val suggestedLinesEstimate: Long,
    val acceptedLinesEstimate: Long,
    val acceptanceByLanguage: Map<String, Double>,
    val totalInteractions: Int,
    val byFeatureType: Map<FeatureType, Int>
)

data class DailyFluency(
    val date: String,
    val score: Int,
    val interactionsPerDay: Double,
    val acceptanceRate: Double?
)

data class ModelUsageStat(
    val modelId: String,
    val displayName: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val pct: Double,
    val avgAcceptanceRate: Double?,
    val avgLatencyMs: Double,
    val totalCostUsd: Double
)

data class ModelComparison(
    val modelId: String,
    val contextWindow: Long,
    val maxPrompt: Long,
    val maxPromptUtilizationPct: Double,
    val premiumMultiplier: Double,
    val avgTtftMs: Double,
    val avgAcceptanceRate: Double?,
    val costPer1kInputUsd: Double,
    val supportsCache: Boolean,
    val usedThisPeriod: Boolean
)

data class ModelRecommendation(
    val modelId: String,
    val reason: String
)

data class TaskContext(
    val estimatedTokens: Long,
    val featureType: FeatureType,
    val budgetConscious: Boolean
)

data class MonthlyProjection(
    val daysElapsed: Int,
    val daysInMonth: Int,
    val actualToDateUsd: Double,
    val projectedMonthTotalUsd: Double,
    val projectedTokens: Long,
    val budgetUsd: Double?,
    val projectedOverBudgetPct: Double?
)
