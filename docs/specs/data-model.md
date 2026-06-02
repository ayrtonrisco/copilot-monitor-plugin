# Data Model — Copilot Monitor Plugin

## Kotlin Domain Models

### `Interaction.kt`
```kotlin
package com.github.copilotmonitor.model

import java.time.Instant

enum class FeatureType {
    COMPLETION_INLINE,  // ghost-text completions
    CHAT_ASK,           // Copilot Chat ask mode
    CHAT_EDIT,          // Copilot Chat edit mode
    CHAT_AGENT,         // Agent mode
    CHAT_PLAN,          // Plan mode
    CLI_SESSION,        // Copilot CLI
    CODE_REVIEW,        // Copilot Code Review
    UNKNOWN
}

enum class FinishReason { STOP, LENGTH, TOOL_CALLS, ERROR, UNKNOWN }

data class Interaction(
    val id: Long = 0,
    val timestamp: Instant,
    val model: String,                          // e.g. "gpt-4o", "claude-sonnet-4-20250514"
    val provider: String,                       // e.g. "openai", "anthropic", "google"
    val featureType: FeatureType,
    val inputTokens: Long,                      // total input (includes cached)
    val outputTokens: Long,
    val cacheReadTokens: Long = 0,              // subset of inputTokens served from cache
    val cacheCreationTokens: Long = 0,          // new tokens written to cache
    val reasoningTokens: Long = 0,              // for thinking/reasoning models
    val latencyMs: Long = -1,                   // total operation duration; -1 = unknown
    val ttftMs: Long = -1,                      // time to first token; -1 = unknown
    val finishReason: FinishReason = FinishReason.UNKNOWN,
    val accepted: Boolean? = null,              // null = not applicable (chat), true/false for completions
    val sessionId: String = "",
    val isEstimated: Boolean = false            // true if token counts are derived, not from API response
)
```

### `SessionSummary.kt`
```kotlin
data class SessionSummary(
    val id: String,                    // UUID
    val startTime: Instant,
    val endTime: Instant?,
    val totalInteractions: Int,
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalCacheReadTokens: Long,
    val avgAcceptanceRate: Double?,    // null if no completions in session
    val modelsUsed: List<String>
)
```

### `DailySummary.kt`
```kotlin
data class DailySummary(
    val date: String,                  // ISO-8601 date "2026-06-02"
    val model: String,
    val featureType: FeatureType,
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalCacheReadTokens: Long,
    val cacheHitRate: Double,          // 0.0–1.0
    val acceptanceRate: Double?,
    val avgLatencyMs: Double,
    val avgTtftMs: Double,
    val costEstimateUsd: Double,
    val interactionCount: Int,
    val errorCount: Int
)
```

### `ModelConfig.kt`
```kotlin
data class ModelConfig(
    val modelId: String,               // matches gen_ai.request.model
    val displayName: String,
    val provider: String,
    val contextWindow: Long,           // theoretical max tokens
    val maxPrompt: Long,               // effective max input tokens (Copilot-enforced)
    val premiumMultiplier: Double,     // 1.0 = standard, >1.0 = premium
    val costPer1kInputUsd: Double,
    val costPer1kOutputUsd: Double,
    val costPer1kCacheReadUsd: Double = 0.0,
    val supportsCache: Boolean = false,
    val lastUpdatedEpoch: Long = System.currentTimeMillis()
)
```

### `ContextWindowStatus.kt`
```kotlin
data class ContextWindowStatus(
    val usedTokensEstimate: Long,
    val maxPromptTokens: Long,
    val utilizationPct: Double,        // 0.0–100.0
    val openTabCount: Int,
    val activeFileSizeChars: Long,
    val selectionSizeChars: Long,
    val currentModel: String,
    val warning: ContextWindowWarning?
)

enum class ContextWindowWarning {
    APPROACHING_LIMIT,   // 50–80% utilization
    CRITICAL,            // >80% — degradation likely
    EXCEEDED             // token limit exceeded error detected in logs
}
```

### `CacheStats.kt`
```kotlin
data class CacheStats(
    val periodLabel: String,           // "today", "7d", "30d"
    val cacheHitRate: Double,          // 0.0–1.0
    val cacheCreationRate: Double,
    val totalCacheReadTokens: Long,
    val totalCacheCreationTokens: Long,
    val estimatedSavingsUsd: Double,
    val recommendation: String?        // null if no recommendation
)
```

---

## SQLite Schema

File location: `<IDE_SYSTEM_DIR>/copilot-monitor/metrics.db`

```sql
-- Schema version tracking
CREATE TABLE IF NOT EXISTS schema_version (
    version     INTEGER PRIMARY KEY,
    applied_at  TEXT NOT NULL,
    description TEXT NOT NULL
);

-- Core interaction log
CREATE TABLE IF NOT EXISTS interactions (
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp               TEXT NOT NULL,          -- ISO-8601 with timezone
    session_id              TEXT NOT NULL DEFAULT '',
    model                   TEXT NOT NULL,
    provider                TEXT NOT NULL DEFAULT '',
    feature_type            TEXT NOT NULL,          -- FeatureType enum value
    input_tokens            INTEGER NOT NULL DEFAULT 0,
    output_tokens           INTEGER NOT NULL DEFAULT 0,
    cache_read_tokens       INTEGER NOT NULL DEFAULT 0,
    cache_creation_tokens   INTEGER NOT NULL DEFAULT 0,
    reasoning_tokens        INTEGER NOT NULL DEFAULT 0,
    latency_ms              INTEGER NOT NULL DEFAULT -1,
    ttft_ms                 INTEGER NOT NULL DEFAULT -1,
    finish_reason           TEXT NOT NULL DEFAULT 'UNKNOWN',
    accepted                INTEGER,                -- NULL / 0 / 1
    is_estimated            INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_interactions_timestamp ON interactions(timestamp);
CREATE INDEX IF NOT EXISTS idx_interactions_model     ON interactions(model);
CREATE INDEX IF NOT EXISTS idx_interactions_session   ON interactions(session_id);

-- Pre-aggregated daily rollups (updated by background job each hour)
CREATE TABLE IF NOT EXISTS daily_summary (
    date                TEXT NOT NULL,
    model               TEXT NOT NULL,
    feature_type        TEXT NOT NULL,
    total_input_tokens  INTEGER NOT NULL DEFAULT 0,
    total_output_tokens INTEGER NOT NULL DEFAULT 0,
    cache_read_tokens   INTEGER NOT NULL DEFAULT 0,
    cache_hit_rate      REAL NOT NULL DEFAULT 0.0,
    acceptance_rate     REAL,
    avg_latency_ms      REAL NOT NULL DEFAULT 0.0,
    avg_ttft_ms         REAL NOT NULL DEFAULT 0.0,
    cost_estimate_usd   REAL NOT NULL DEFAULT 0.0,
    interaction_count   INTEGER NOT NULL DEFAULT 0,
    error_count         INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (date, model, feature_type)
);

-- Model configuration and pricing
CREATE TABLE IF NOT EXISTS model_config (
    model_id                TEXT PRIMARY KEY,
    display_name            TEXT NOT NULL,
    provider                TEXT NOT NULL DEFAULT '',
    context_window          INTEGER NOT NULL DEFAULT 128000,
    max_prompt              INTEGER NOT NULL DEFAULT 64000,
    premium_multiplier      REAL NOT NULL DEFAULT 1.0,
    cost_per_1k_input_usd   REAL NOT NULL DEFAULT 0.0,
    cost_per_1k_output_usd  REAL NOT NULL DEFAULT 0.0,
    cost_per_1k_cache_usd   REAL NOT NULL DEFAULT 0.0,
    supports_cache          INTEGER NOT NULL DEFAULT 0,
    last_updated            INTEGER NOT NULL         -- epoch millis
);

-- Alert history
CREATE TABLE IF NOT EXISTS alerts (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp     TEXT NOT NULL,
    type          TEXT NOT NULL,     -- BUDGET_75, BUDGET_90, BUDGET_100, CONTEXT_CRITICAL, CACHE_LOW, RATE_LIMIT
    severity      TEXT NOT NULL,     -- INFO, WARNING, ERROR
    message       TEXT NOT NULL,
    acknowledged  INTEGER NOT NULL DEFAULT 0
);
```

---

## Initial Model Config Seed Data

Seed `model_config` on first install. Update via `ModelConfigRepository.refreshFromRemote()`:

```kotlin
val SEED_MODELS = listOf(
    ModelConfig("gpt-4o",                    "GPT-4o",                "openai",    128_000,  64_000, 1.0,  0.0025, 0.010, 0.0,    false),
    ModelConfig("gpt-4.1",                   "GPT-4.1",               "openai",  1_000_000, 128_000, 1.0,  0.002,  0.008, 0.0,    false),
    ModelConfig("gpt-5.2-codex",             "GPT-5.2 Codex",         "openai",    400_000, 400_000, 2.0,  0.003,  0.015, 0.0,    false),
    ModelConfig("claude-sonnet-4-20250514",  "Claude Sonnet 4",       "anthropic", 200_000, 128_000, 1.0,  0.003,  0.015, 0.0003, true),
    ModelConfig("claude-opus-4-20250514",    "Claude Opus 4",         "anthropic", 200_000, 128_000, 2.0,  0.015,  0.075, 0.0015, true),
    ModelConfig("gemini-2.5-pro",            "Gemini 2.5 Pro",        "google",  1_000_000, 128_000, 1.0,  0.00125,0.005, 0.0,    false),
    ModelConfig("o3",                        "o3",                    "openai",    200_000, 128_000, 3.0,  0.010,  0.040, 0.0,    false),
)
```
