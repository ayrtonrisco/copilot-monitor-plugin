package com.github.copilotmonitor.services

import com.github.copilotmonitor.model.CacheStats
import com.github.copilotmonitor.model.ModelConfig
import com.github.copilotmonitor.model.RecommendationPriority
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CacheAnalysisServiceTest {

    private fun makeService(supportsCache: Boolean = true): CacheAnalysisService {
        val mockRepo = mockk<ModelConfigRepository>()
        every { mockRepo.get(any()) } returns ModelConfig(
            modelId = "gpt-4o",
            displayName = "GPT-4o",
            provider = "openai",
            contextWindow = 128_000,
            maxPrompt = 64_000,
            premiumMultiplier = 1.0,
            costPer1kInputUsd = 0.0025,
            costPer1kOutputUsd = 0.010,
            supportsCache = supportsCache
        )
        return object : CacheAnalysisService() {
            override fun getModelRepo(): ModelConfigRepository = mockRepo
        }
    }

    @Test
    fun `generateRecommendations returns HIGH priority when hit rate is low`() {
        val service = makeService()
        val stats = CacheStats(
            periodLabel = "7d",
            cacheHitRate = 0.15,
            cacheCreationRate = 0.1,
            totalCacheReadTokens = 1500,
            totalCacheCreationTokens = 1000,
            estimatedSavingsUsd = 0.01,
            recommendation = null
        )
        val recs = service.generateRecommendations(stats)
        assertTrue(recs.any { it.priority == RecommendationPriority.HIGH })
    }

    @Test
    fun `generateRecommendations returns INFO when hit rate is good`() {
        val service = makeService()
        val stats = CacheStats(
            periodLabel = "7d",
            cacheHitRate = 0.75,
            cacheCreationRate = 0.1,
            totalCacheReadTokens = 75000,
            totalCacheCreationTokens = 10000,
            estimatedSavingsUsd = 0.5,
            recommendation = null
        )
        val recs = service.generateRecommendations(stats)
        assertTrue(recs.any { it.priority == RecommendationPriority.INFO })
        assertTrue(recs.none { it.priority == RecommendationPriority.HIGH })
    }

    @Test
    fun `generateRecommendations reports cache-unsupported model`() {
        val service = makeService(supportsCache = false)
        val stats = CacheStats(
            periodLabel = "7d",
            cacheHitRate = 0.5,
            cacheCreationRate = 0.1,
            totalCacheReadTokens = 50000,
            totalCacheCreationTokens = 10000,
            estimatedSavingsUsd = 0.1,
            recommendation = null
        )
        val recs = service.generateRecommendations(stats)
        assertTrue(recs.any { it.message.contains("does not support prompt caching") })
    }
}
