package com.github.copilotmonitor.services

import com.github.copilotmonitor.model.FeatureType
import com.github.copilotmonitor.model.Interaction
import com.github.copilotmonitor.model.ModelConfig
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class CostEstimationServiceTest {

    @Test
    fun `estimateForInteraction computes cost correctly for gpt-4o`() {
        val mockRepo = mockk<ModelConfigRepository>()
        every { mockRepo.get("gpt-4o") } returns ModelConfig(
            modelId = "gpt-4o",
            displayName = "GPT-4o",
            provider = "openai",
            contextWindow = 128_000,
            maxPrompt = 64_000,
            premiumMultiplier = 1.0,
            costPer1kInputUsd = 0.0025,
            costPer1kOutputUsd = 0.010,
            costPer1kCacheReadUsd = 0.0
        )

        val service = createService(mockRepo)
        val interaction = Interaction(
            timestamp = Instant.now(),
            model = "gpt-4o",
            provider = "openai",
            featureType = FeatureType.CHAT_ASK,
            inputTokens = 4000,
            outputTokens = 500,
            cacheReadTokens = 0
        )

        // (4000/1000 * 0.0025) + (500/1000 * 0.010) = 0.010 + 0.005 = 0.015
        val cost = service.estimateForInteraction(interaction)
        assertEquals(0.015, cost, 0.0001)
    }

    @Test
    fun `estimateForInteraction applies cache read discount`() {
        val mockRepo = mockk<ModelConfigRepository>()
        every { mockRepo.get("claude-sonnet-4-20250514") } returns ModelConfig(
            modelId = "claude-sonnet-4-20250514",
            displayName = "Claude Sonnet 4",
            provider = "anthropic",
            contextWindow = 200_000,
            maxPrompt = 128_000,
            premiumMultiplier = 1.0,
            costPer1kInputUsd = 0.003,
            costPer1kOutputUsd = 0.015,
            costPer1kCacheReadUsd = 0.0003,
            supportsCache = true
        )

        val service = createService(mockRepo)
        val interaction = Interaction(
            timestamp = Instant.now(),
            model = "claude-sonnet-4-20250514",
            provider = "anthropic",
            featureType = FeatureType.CHAT_AGENT,
            inputTokens = 10000,
            outputTokens = 1000,
            cacheReadTokens = 8000
        )

        // freshInput = 10000 - 8000 = 2000
        // inputCost = (2000/1000 * 0.003) = 0.006
        // cacheCost = (8000/1000 * 0.0003) = 0.0024
        // outputCost = (1000/1000 * 0.015) = 0.015
        // total = 0.006 + 0.0024 + 0.015 = 0.0234
        val cost = service.estimateForInteraction(interaction)
        assertEquals(0.0234, cost, 0.0001)
    }

    @Test
    fun `estimateForInteraction applies premium multiplier`() {
        val mockRepo = mockk<ModelConfigRepository>()
        every { mockRepo.get("o3") } returns ModelConfig(
            modelId = "o3",
            displayName = "o3",
            provider = "openai",
            contextWindow = 200_000,
            maxPrompt = 128_000,
            premiumMultiplier = 3.0,
            costPer1kInputUsd = 0.010,
            costPer1kOutputUsd = 0.040,
            costPer1kCacheReadUsd = 0.0
        )

        val service = createService(mockRepo)
        val interaction = Interaction(
            timestamp = Instant.now(),
            model = "o3",
            provider = "openai",
            featureType = FeatureType.CHAT_ASK,
            inputTokens = 1000,
            outputTokens = 1000
        )

        // (1 * 0.010 + 1 * 0.040) * 3.0 = 0.05 * 3.0 = 0.15
        val cost = service.estimateForInteraction(interaction)
        assertEquals(0.15, cost, 0.0001)
    }

    private fun createService(mockRepo: ModelConfigRepository): CostEstimationService {
        return object : CostEstimationService() {
            override fun getModelRepo(): ModelConfigRepository = mockRepo
        }
    }
}
