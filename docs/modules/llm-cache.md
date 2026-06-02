# Module: LLM Cache Analysis

## Purpose

Track and analyze GitHub Copilot's prompt caching behavior. Caching reduces latency
and cost by reusing previously processed tokens. Visibility into cache effectiveness
enables optimization of how context is structured.

## Key Metrics

| Metric                | Formula                                           | Good value |
|-----------------------|---------------------------------------------------|------------|
| Cache Hit Rate        | `cache_read_tokens / input_tokens * 100`          | > 60%      |
| Cache Creation Rate   | `cache_creation_tokens / input_tokens * 100`      | < 20%      |
| Cache Efficiency Score| `(hit_rate * 0.6) + (savings_ratio * 0.4)`       | > 70       |
| Savings Ratio         | `cache_read_tokens * cost_delta / total_cost`     | > 30%      |

## OTel Attribute Mapping

These attributes from GenAI Semantic Conventions map to Copilot session log fields:

| OTel Attribute                         | Session log field            | Kotlin model field        |
|----------------------------------------|------------------------------|---------------------------|
| `gen_ai.usage.input_tokens`            | `usage.prompt_tokens`        | `inputTokens`             |
| `gen_ai.usage.output_tokens`           | `usage.completion_tokens`    | `outputTokens`            |
| `gen_ai.usage.cache_read.input_tokens` | `usage.cache_read_input_tokens` | `cacheReadTokens`      |
| `gen_ai.usage.cache_creation.input_tokens` | `usage.cache_creation_input_tokens` | `cacheCreationTokens` |

## What Affects Cache Effectiveness

1. **System prompt stability** — changes to Copilot instructions or `.github/copilot-instructions.md` invalidate cache
2. **Open file order consistency** — same files in different tab order = cache miss
3. **Common prefix length** — longer identical prefix between requests = higher hit rate
4. **Model support** — only some models/providers support prompt caching (see `ModelConfig.supportsCache`)

## Implementation Tasks

- [ ] **Task CACHE-1:** `CacheAnalysisService` 
  ```kotlin
  class CacheAnalysisService : ApplicationService {
      fun getStats(period: Period): CacheStats
      fun getHitRateTrend(days: Int): List<DailyHitRate>
      fun generateRecommendations(stats: CacheStats): List<CacheRecommendation>
  }
  ```

- [ ] **Task CACHE-2:** Hit rate trend computation
  - Query `daily_summary` grouped by date for last N days
  - Compute 7-day rolling average for smoothing
  - Identify anomalous drops (> 20 percentage points in one day)

- [ ] **Task CACHE-3:** Savings estimation
  ```kotlin
  fun estimateSavingsUsd(cacheReadTokens: Long, model: String): Double {
      val config = modelConfigRepo.get(model)
      // Cache reads are typically 10% of standard input cost
      val normalCost = (cacheReadTokens / 1000.0) * config.costPer1kInputUsd
      val cacheCost  = (cacheReadTokens / 1000.0) * config.costPer1kCacheReadUsd
      return normalCost - cacheCost
  }
  ```

- [ ] **Task CACHE-4:** Recommendations engine
  ```kotlin
  data class CacheRecommendation(val priority: Priority, val message: String, val action: String?)
  
  fun generateRecommendations(stats: CacheStats): List<CacheRecommendation> {
      return buildList {
          if (stats.cacheHitRate < 0.3) add(CacheRecommendation(HIGH,
              "Cache hit rate is low (${stats.cacheHitRate.pct}%). " +
              "Keep the same files open between requests.",
              "Open Context panel to see current tab composition"))
          if (!modelSupportsCache(currentModel())) add(CacheRecommendation(INFO,
              "Current model (${currentModel()}) does not support prompt caching.",
              "Switch to Claude Sonnet or a cache-capable model for cost savings"))
      }
  }
  ```

- [ ] **Task CACHE-5:** Cache panel webview
  - Donut chart: `cache_read` (blue) + `cache_creation` (light blue) + `other_input` (gray) + `output` (green)
  - Hit rate gauge with period selector (today / 7d / 30d)
  - Trend line chart (30d)
  - Recommendations list with priority indicators

- [ ] **Task CACHE-6:** Alert when hit rate drops below threshold
  - Configurable threshold (default: 30%)
  - Alert type: `CACHE_HIT_RATE_LOW`
  - Debounce: fire once per 24h maximum

## Cache Panel Data Contract (JSON to webview)

```json
{
  "period": "7d",
  "cacheHitRate": 0.62,
  "cacheCreationRate": 0.15,
  "totalCacheReadTokens": 284000,
  "totalCacheCreationTokens": 68000,
  "estimatedSavingsUsd": 0.48,
  "trend": [
    { "date": "2026-05-27", "hitRate": 0.71 },
    { "date": "2026-05-28", "hitRate": 0.68 }
  ],
  "recommendations": [
    { "priority": "INFO", "message": "Good cache efficiency. Keep files consistent between sessions." }
  ],
  "donut": {
    "cacheRead": 284000,
    "cacheCreation": 68000,
    "freshInput": 106000,
    "output": 84000
  }
}
```
