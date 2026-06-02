package com.github.copilotmonitor.services

import com.github.copilotmonitor.model.FeatureType
import com.github.copilotmonitor.model.ModelComparison
import com.github.copilotmonitor.model.ModelRecommendation
import com.github.copilotmonitor.model.ModelUsageStat
import com.github.copilotmonitor.model.TaskContext
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
class ModelAnalysisService {

    private val storage: MetricsStorageService by lazy { service() }
    private val modelRepo: ModelConfigRepository by lazy { service() }
    private val costService: CostEstimationService by lazy { service() }
    private val settings get() = com.github.copilotmonitor.settings.CopilotMonitorSettings.getInstance()

    fun getModelUsageDistribution(days: Int): List<ModelUsageStat> {
        val interactions = storage.getInteractionsForPeriod(days)
        if (interactions.isEmpty()) return emptyList()

        val totalTokens = interactions.sumOf { it.inputTokens + it.outputTokens }.toDouble()

        return interactions.groupBy { it.model }.map { (modelId, group) ->
            val config = modelRepo.get(modelId)
            val inputTokens = group.sumOf { it.inputTokens }
            val outputTokens = group.sumOf { it.outputTokens }
            val tokens = inputTokens + outputTokens
            val cost = group.sumOf { costService.estimateForInteraction(it) }
            val acceptance = group.filter { it.accepted != null }.let { cs ->
                if (cs.isEmpty()) null
                else cs.count { it.accepted == true }.toDouble() / cs.size
            }
            val avgLatency = group.filter { it.latencyMs >= 0 }
                .map { it.latencyMs }.let { ls ->
                    if (ls.isEmpty()) 0.0 else ls.average()
                }

            ModelUsageStat(
                modelId = modelId,
                displayName = config.displayName,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                pct = if (totalTokens > 0) tokens / totalTokens * 100 else 0.0,
                avgAcceptanceRate = acceptance,
                avgLatencyMs = avgLatency,
                totalCostUsd = cost
            )
        }.sortedByDescending { it.inputTokens + it.outputTokens }
    }

    fun getModelComparison(): List<ModelComparison> {
        val usedModels = storage.getInteractionsForPeriod(30).map { it.model }.toSet()
        return modelRepo.getAll().map { config ->
            val interactions = storage.getInteractionsForPeriod(30)
                .filter { it.model == config.modelId }

            val acceptance = interactions.filter { it.accepted != null }.let { cs ->
                if (cs.isEmpty()) null
                else cs.count { it.accepted == true }.toDouble() / cs.size
            }
            val avgTtft = interactions.filter { it.ttftMs >= 0 }
                .map { it.ttftMs }.let { ls ->
                    if (ls.isEmpty()) 0.0 else ls.average()
                }

            ModelComparison(
                modelId = config.modelId,
                contextWindow = config.contextWindow,
                maxPrompt = config.maxPrompt,
                maxPromptUtilizationPct = config.maxPrompt.toDouble() / config.contextWindow * 100,
                premiumMultiplier = config.premiumMultiplier,
                avgTtftMs = avgTtft,
                avgAcceptanceRate = acceptance,
                costPer1kInputUsd = config.costPer1kInputUsd,
                supportsCache = config.supportsCache,
                usedThisPeriod = config.modelId in usedModels
            )
        }
    }

    fun getRecommendation(taskContext: TaskContext): ModelRecommendation? {
        return when {
            taskContext.estimatedTokens > 100_000 ->
                ModelRecommendation("gpt-5.2-codex", "Only model with 400K effective context window")
            taskContext.estimatedTokens < 5_000 && taskContext.budgetConscious ->
                ModelRecommendation("gpt-4o", "Best cost/quality ratio for small tasks")
            taskContext.featureType == FeatureType.CHAT_AGENT ->
                ModelRecommendation("claude-sonnet-4-20250514", "Best cache hit rate for long agent sessions")
            else -> null
        }
    }
}
