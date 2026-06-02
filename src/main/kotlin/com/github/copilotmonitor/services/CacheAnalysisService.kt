package com.github.copilotmonitor.services

import com.github.copilotmonitor.CopilotMonitorTopics
import com.github.copilotmonitor.model.CacheRecommendation
import com.github.copilotmonitor.model.CacheStats
import com.github.copilotmonitor.model.DailyHitRate
import com.github.copilotmonitor.model.RecommendationPriority
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Service(Service.Level.APP)
open class CacheAnalysisService {

    private val logger = thisLogger()
    private val storage: MetricsStorageService by lazy { service() }
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private var currentModel: String = "gpt-4o"
    private var lastAlertDate: LocalDate? = null

    protected open fun getModelRepo(): ModelConfigRepository = service()

    fun setCurrentModel(model: String) { currentModel = model }

    fun getStats(days: Int = 7): CacheStats {
        val interactions = storage.getInteractionsForPeriod(days)
        if (interactions.isEmpty()) {
            return CacheStats("${days}d", 0.0, 0.0, 0, 0, 0.0, null)
        }

        val totalInput = interactions.sumOf { it.inputTokens }
        val totalCacheRead = interactions.sumOf { it.cacheReadTokens }
        val totalCacheCreation = interactions.sumOf { it.cacheCreationTokens }

        val hitRate = if (totalInput > 0) totalCacheRead.toDouble() / totalInput else 0.0
        val creationRate = if (totalInput > 0) totalCacheCreation.toDouble() / totalInput else 0.0
        val savings = estimateSavingsUsd(totalCacheRead, currentModel)

        val recs = generateRecommendations(
            CacheStats("${days}d", hitRate, creationRate, totalCacheRead, totalCacheCreation, savings, null)
        )

        return CacheStats(
            periodLabel = "${days}d",
            cacheHitRate = hitRate,
            cacheCreationRate = creationRate,
            totalCacheReadTokens = totalCacheRead,
            totalCacheCreationTokens = totalCacheCreation,
            estimatedSavingsUsd = savings,
            recommendation = recs.firstOrNull()?.message
        )
    }

    fun getHitRateTrend(days: Int = 30): List<DailyHitRate> {
        val summaries = storage.getDailySummary(days)
        return summaries
            .groupBy { it.date }
            .map { (date, entries) ->
                val totalInput = entries.sumOf { it.totalInputTokens }
                val totalRead = entries.sumOf { it.totalCacheReadTokens }
                val rate = if (totalInput > 0) totalRead.toDouble() / totalInput else 0.0
                DailyHitRate(date, rate)
            }
            .sortedBy { it.date }
    }

    fun generateRecommendations(stats: CacheStats): List<CacheRecommendation> {
        return buildList {
            if (stats.cacheHitRate < 0.3) {
                add(CacheRecommendation(
                    priority = RecommendationPriority.HIGH,
                    message = "Cache hit rate is low (${(stats.cacheHitRate * 100).toInt()}%). " +
                        "Keep the same files open between requests.",
                    action = "Open Context panel to see current tab composition"
                ))
            }
            val config = getModelRepo().get(currentModel)
            if (!config.supportsCache) {
                add(CacheRecommendation(
                    priority = RecommendationPriority.INFO,
                    message = "Current model ($currentModel) does not support prompt caching.",
                    action = "Switch to Claude Sonnet 4 for cache savings"
                ))
            }
            if (stats.cacheHitRate > 0.6) {
                add(CacheRecommendation(
                    priority = RecommendationPriority.INFO,
                    message = "Good cache efficiency. Keep files consistent between sessions.",
                    action = null
                ))
            }
        }
    }

    fun checkAndFireAlert() {
        val today = LocalDate.now()
        if (lastAlertDate == today) return

        val stats = getStats(1)
        val threshold = com.github.copilotmonitor.settings.CopilotMonitorSettings.getInstance()
            .cacheHitRateLowThreshold

        if (stats.cacheHitRate > 0 && stats.cacheHitRate < threshold) {
            lastAlertDate = today
            ApplicationManager.getApplication().messageBus
                .syncPublisher(CopilotMonitorTopics.CACHE_HIT_RATE_ALERT)
                .onCacheHitRateLow(stats.cacheHitRate, threshold)
        }
    }

    private fun estimateSavingsUsd(cacheReadTokens: Long, model: String): Double {
        val config = getModelRepo().get(model)
        val normalCost = (cacheReadTokens / 1000.0) * config.costPer1kInputUsd
        val cacheCost = (cacheReadTokens / 1000.0) * config.costPer1kCacheReadUsd
        return normalCost - cacheCost
    }
}
