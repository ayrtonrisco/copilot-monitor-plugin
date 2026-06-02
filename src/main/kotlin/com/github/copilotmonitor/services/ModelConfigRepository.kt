package com.github.copilotmonitor.services

import com.github.copilotmonitor.model.ModelConfig
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import java.sql.ResultSet

@Service(Service.Level.APP)
class ModelConfigRepository {

    private val logger = thisLogger()
    private val storage: MetricsStorageService by lazy { service() }

    private val seedModels = listOf(
        ModelConfig("gpt-4o",                   "GPT-4o",          "openai",    128_000,  64_000, 1.0, 0.0025,  0.010,  0.0,    false),
        ModelConfig("gpt-4.1",                  "GPT-4.1",         "openai",  1_000_000, 128_000, 1.0, 0.002,   0.008,  0.0,    false),
        ModelConfig("gpt-5.2-codex",            "GPT-5.2 Codex",   "openai",    400_000, 400_000, 2.0, 0.003,   0.015,  0.0,    false),
        ModelConfig("claude-sonnet-4-20250514", "Claude Sonnet 4", "anthropic", 200_000, 128_000, 1.0, 0.003,   0.015,  0.0003, true),
        ModelConfig("claude-opus-4-20250514",   "Claude Opus 4",   "anthropic", 200_000, 128_000, 2.0, 0.015,   0.075,  0.0015, true),
        ModelConfig("gemini-2.5-pro",           "Gemini 2.5 Pro",  "google",  1_000_000, 128_000, 1.0, 0.00125, 0.005,  0.0,    false),
        ModelConfig("o3",                       "o3",              "openai",    200_000, 128_000, 3.0, 0.010,   0.040,  0.0,    false),
    )

    private val cache: MutableMap<String, ModelConfig> = mutableMapOf()

    fun init() {
        ensureSeedData()
        loadAll()
    }

    private fun ensureSeedData() {
        storage.withConnection { conn ->
            seedModels.forEach { model ->
                conn.prepareStatement("""
                    INSERT OR IGNORE INTO model_config
                        (model_id, display_name, provider, context_window, max_prompt, premium_multiplier,
                         cost_per_1k_input_usd, cost_per_1k_output_usd, cost_per_1k_cache_usd, supports_cache, last_updated)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { ps ->
                    ps.setString(1, model.modelId)
                    ps.setString(2, model.displayName)
                    ps.setString(3, model.provider)
                    ps.setLong(4, model.contextWindow)
                    ps.setLong(5, model.maxPrompt)
                    ps.setDouble(6, model.premiumMultiplier)
                    ps.setDouble(7, model.costPer1kInputUsd)
                    ps.setDouble(8, model.costPer1kOutputUsd)
                    ps.setDouble(9, model.costPer1kCacheReadUsd)
                    ps.setInt(10, if (model.supportsCache) 1 else 0)
                    ps.setLong(11, model.lastUpdatedEpoch)
                    ps.executeUpdate()
                }
            }
        }
    }

    private fun loadAll() {
        storage.withConnection { conn ->
            conn.prepareStatement("SELECT * FROM model_config").use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val m = rs.toModelConfig()
                        cache[m.modelId] = m
                    }
                }
            }
        }
    }

    fun get(modelId: String): ModelConfig {
        return cache[modelId]
            ?: cache.values.firstOrNull { modelId.lowercase().contains(it.modelId.lowercase()) }
            ?: ModelConfig(
                modelId = modelId,
                displayName = modelId,
                provider = inferProvider(modelId),
                contextWindow = 128_000,
                maxPrompt = 64_000,
                premiumMultiplier = 1.0,
                costPer1kInputUsd = 0.003,
                costPer1kOutputUsd = 0.015
            )
    }

    fun getAll(): List<ModelConfig> = cache.values.toList()

    fun getMaxPrompt(modelId: String): Long = get(modelId).maxPrompt

    fun getLastKnownModel(): String? {
        return storage.withConnection { conn ->
            try {
                conn.prepareStatement(
                    "SELECT model FROM interactions WHERE model != 'unknown' ORDER BY timestamp DESC LIMIT 1"
                ).use { ps ->
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
            } catch (_: Exception) { null }
        }
    }

    fun upsert(model: ModelConfig) {
        cache[model.modelId] = model
        storage.withConnection { conn ->
            conn.prepareStatement("""
                INSERT OR REPLACE INTO model_config
                    (model_id, display_name, provider, context_window, max_prompt, premium_multiplier,
                     cost_per_1k_input_usd, cost_per_1k_output_usd, cost_per_1k_cache_usd, supports_cache, last_updated)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { ps ->
                ps.setString(1, model.modelId)
                ps.setString(2, model.displayName)
                ps.setString(3, model.provider)
                ps.setLong(4, model.contextWindow)
                ps.setLong(5, model.maxPrompt)
                ps.setDouble(6, model.premiumMultiplier)
                ps.setDouble(7, model.costPer1kInputUsd)
                ps.setDouble(8, model.costPer1kOutputUsd)
                ps.setDouble(9, model.costPer1kCacheReadUsd)
                ps.setInt(10, if (model.supportsCache) 1 else 0)
                ps.setLong(11, model.lastUpdatedEpoch)
                ps.executeUpdate()
            }
        }
    }

    private fun inferProvider(modelId: String): String = when {
        modelId.contains("claude") -> "anthropic"
        modelId.contains("gemini") -> "google"
        modelId.startsWith("gpt") || modelId == "o3" || modelId.startsWith("o1") -> "openai"
        else -> "unknown"
    }

    private fun ResultSet.toModelConfig() = ModelConfig(
        modelId = getString("model_id"),
        displayName = getString("display_name"),
        provider = getString("provider") ?: "",
        contextWindow = getLong("context_window"),
        maxPrompt = getLong("max_prompt"),
        premiumMultiplier = getDouble("premium_multiplier"),
        costPer1kInputUsd = getDouble("cost_per_1k_input_usd"),
        costPer1kOutputUsd = getDouble("cost_per_1k_output_usd"),
        costPer1kCacheReadUsd = getDouble("cost_per_1k_cache_usd"),
        supportsCache = getInt("supports_cache") == 1,
        lastUpdatedEpoch = getLong("last_updated")
    )

    companion object {
        fun getInstance(): ModelConfigRepository = service()
    }
}
