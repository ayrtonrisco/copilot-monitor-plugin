package com.github.copilotmonitor.model

data class ModelConfig(
    val modelId: String,
    val displayName: String,
    val provider: String,
    val contextWindow: Long,
    val maxPrompt: Long,
    val premiumMultiplier: Double,
    val costPer1kInputUsd: Double,
    val costPer1kOutputUsd: Double,
    val costPer1kCacheReadUsd: Double = 0.0,
    val supportsCache: Boolean = false,
    val lastUpdatedEpoch: Long = System.currentTimeMillis()
)
