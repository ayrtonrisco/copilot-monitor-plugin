# Module: Performance & Latency

## Purpose

Track response quality and speed metrics to detect degradation patterns,
especially those correlated with high context window utilization.

## Key Metrics

| Metric          | OTel Attribute                        | Source                  |
|-----------------|---------------------------------------|-------------------------|
| TTFT            | (derived from log timestamps)         | Session log             |
| Total latency   | `gen_ai.client.operation.duration`    | Session log `latencyMs` |
| Tokens/second   | `outputTokens / latencyMs * 1000`     | Computed                |
| Error rate      | Interactions with `finish_reason=ERROR` | Session log           |
| Length truncation rate | `finish_reason=LENGTH` / total | Session log          |
| Tool call loops | Repeated tool_call events in .jsonl   | Session log             |

## Degradation Detection

### Lost-in-the-Middle Heuristic

When context utilization > 80%, track response quality signals:
- `acceptanceRate` of completions in high-context sessions vs. baseline
- Increase in `LENGTH` finish reasons (model runs out of output budget)
- Increase in repeated tool calls (agent is re-reading already-processed files)

```kotlin
data class DegradationSignal(
    val type: DegradationType,
    val contextUtilizationPct: Double,
    val evidence: String,
    val timestamp: Instant
)

enum class DegradationType {
    HIGH_CONTEXT_LOW_ACCEPTANCE,  // utilization > 80%, acceptance drops > 15%
    RESPONSE_TRUNCATION,          // finish_reason=LENGTH more than 5% of interactions
    TOOL_CALL_LOOP,               // same tool called 3+ times in one session
    LATENCY_SPIKE                 // latency > 3x rolling average
}
```

## Implementation Tasks

- [ ] **Task PERF-1:** Parse `latencyMs` and `ttftMs` from session logs
  - For `.json` files: read `latencyMs` field directly if present
  - For `.jsonl` files: derive from timestamp delta between `request` events

- [ ] **Task PERF-2:** `PerformanceService`
  ```kotlin
  class PerformanceService : ApplicationService {
      fun getLatencyStats(period: Period, model: String? = null): LatencyStats
      fun getTtftStats(period: Period): LatencyStats
      fun getErrorRate(period: Period): Double
      fun getFinishReasonDistribution(period: Period): Map<FinishReason, Int>
      fun detectDegradation(): List<DegradationSignal>
  }
  
  data class LatencyStats(
      val p50Ms: Long, val p90Ms: Long, val p99Ms: Long,
      val avgMs: Double, val minMs: Long, val maxMs: Long,
      val sampleCount: Int
  )
  ```

- [ ] **Task PERF-3:** TTFT histogram (performance panel)
  - Bucket interactions into: <200ms, 200-500ms, 500ms-1s, 1-2s, 2-5s, >5s
  - Display as bar chart per model

- [ ] **Task PERF-4:** Latency by model comparison
  - Group by model, compute p50/p90 latency
  - Show as horizontal bar chart sorted by p50

- [ ] **Task PERF-5:** Rate limit detection
  - Pattern in logs: `429`, `rate limit`, `too many requests`
  - Record in `alerts` table with `type = "RATE_LIMIT"`
  - Show rate limit events on performance panel timeline

- [ ] **Task PERF-6:** Token limit exceeded detection
  - Pattern: `model_max_prompt_tokens_exceeded`, `token limit exceeded`
  - Record as `CONTEXT_WINDOW_EXCEEDED` alert
  - Correlate with context window utilization at that time

## Performance Panel Data Contract

```json
{
  "latency": {
    "today":   { "p50": 820, "p90": 1840, "p99": 3200, "avg": 950 },
    "7d_avg":  { "p50": 780, "p90": 1620, "p99": 2800, "avg": 890 }
  },
  "ttft": {
    "today":  { "p50": 380, "p90": 720, "avg": 420 }
  },
  "errorRate":  0.018,
  "finishReasons": { "STOP": 94, "LENGTH": 4, "TOOL_CALLS": 2 },
  "histogram": [
    { "bucket": "<200ms",    "count": 8  },
    { "bucket": "200-500ms", "count": 34 },
    { "bucket": "500ms-1s",  "count": 28 },
    { "bucket": "1-2s",      "count": 15 },
    { "bucket": "2-5s",      "count": 10 },
    { "bucket": ">5s",       "count": 5  }
  ],
  "byModel": [
    { "model": "gpt-4o",                   "p50": 620, "p90": 1200 },
    { "model": "claude-sonnet-4-20250514", "p50": 940, "p90": 1900 }
  ],
  "degradationSignals": [],
  "recentAlerts": [
    { "ts": "2026-06-02T09:14:00Z", "type": "RATE_LIMIT", "message": "Rate limit hit on gpt-4o" }
  ]
}
```
