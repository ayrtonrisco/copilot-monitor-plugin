# Module: Productivity Metrics

## Purpose

Measure the actual value Copilot delivers: how often suggestions are accepted,
how much code is generated, and a composite AI fluency score.

## Key Metrics

| Metric                | How to capture                                              |
|-----------------------|-------------------------------------------------------------|
| Acceptance Rate       | `accepted=true / total completions with accepted!=null`    |
| Lines suggested       | `outputTokens * 0.6` (approx; 1 token ≈ 0.6 lines of code)|
| Lines accepted        | `accepted=true` interactions × avg output tokens           |
| Fluency Score         | Composite (see below)                                       |
| Interactions/hour     | Interaction count / active IDE hours                        |

## Fluency Score Formula

Score 0–100 inspired by AI Engineering Fluency:

```kotlin
fun computeFluencyScore(stats: PeriodStats): Int {
    val usageFrequency  = minOf(stats.interactionsPerDay / 20.0, 1.0) * 30  // up to 30 pts
    val featureDiversity = minOf(stats.uniqueFeatureTypes.size / 4.0, 1.0) * 20  // up to 20 pts
    val acceptanceQuality = (stats.acceptanceRate ?: 0.5) * 30               // up to 30 pts
    val tokenEfficiency  = minOf(stats.outputTokens.toDouble() / stats.inputTokens, 0.5) / 0.5 * 20 // up to 20 pts
    return (usageFrequency + featureDiversity + acceptanceQuality + tokenEfficiency).toInt()
}
```

## Implementation Tasks

- [ ] **Task PROD-1:** `ProductivityService`
  ```kotlin
  class ProductivityService : ApplicationService {
      fun getAcceptanceRate(period: Period, featureType: FeatureType? = null): Double?
      fun getCodeGenerationStats(period: Period): CodeGenerationStats
      fun computeFluencyScore(period: Period): Int
      fun getFluencyTrend(days: Int): List<DailyFluency>
  }

  data class CodeGenerationStats(
      val suggestedLinesEstimate: Long,
      val acceptedLinesEstimate: Long,
      val acceptanceByLanguage: Map<String, Double>,  // requires lang tag in session logs
      val totalInteractions: Int,
      val byFeatureType: Map<FeatureType, Int>
  )
  ```

- [ ] **Task PROD-2:** Overview panel KPI tiles
  - "Today" tile: interactions count + acceptance rate
  - "Fluency Score" tile: score + badge (Novice / Practitioner / Fluent / Expert)
  - Trend arrow vs. 7-day average

- [ ] **Task PROD-3:** Language breakdown (best-effort)
  - If session log includes language tag, group acceptance by language
  - Fallback: group by open file extension from context

---

# Module: Model Analysis

## Purpose

Compare performance, cost, and acceptance rate across the models the developer
uses, and surface recommendations for better model selection.

## Implementation Tasks

- [ ] **Task MODEL-1:** `ModelAnalysisService`
  ```kotlin
  class ModelAnalysisService : ApplicationService {
      fun getModelUsageDistribution(period: Period): List<ModelUsageStat>
      fun getModelComparison(): List<ModelComparison>
      fun getRecommendation(taskContext: TaskContext): ModelRecommendation
  }
  
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
      val maxPromptUtilizationPct: Double,  // maxPrompt / contextWindow
      val premiumMultiplier: Double,
      val avgTtftMs: Double,
      val avgAcceptanceRate: Double?,
      val costPer1kInputUsd: Double,
      val supportsCache: Boolean,
      val usedThisPeriod: Boolean
  )
  ```

- [ ] **Task MODEL-2:** Recommendation engine
  ```kotlin
  fun getRecommendation(taskContext: TaskContext): ModelRecommendation {
      return when {
          taskContext.estimatedTokens > 100_000 ->
              ModelRecommendation("gpt-5.2-codex", "Only model with 400K effective context window")
          taskContext.estimatedTokens < 5_000 && settings.budgetConscious ->
              ModelRecommendation("gpt-4o", "Best cost/quality ratio for small tasks")
          taskContext.featureType == CHAT_AGENT ->
              ModelRecommendation("claude-sonnet-4-20250514", "Best cache hit rate for long agent sessions")
          else -> null  // no specific recommendation
      }
  }
  ```

- [ ] **Task MODEL-3:** Context window gap visualization
  - For each model: show `max_prompt` bar and `context_window` bar
  - Highlight the gap in red with label "Inaccessible via Copilot"
  - Link to GitHub issue/discussion about context window limits

- [ ] **Task MODEL-4:** Model panel comparison table
  - Columns: Model | Provider | Max Context | Copilot Limit | Utilization% | Premium | TTFT p50 | Accept% | $/1K | Cache
  - Sortable by each column
  - Highlight currently active model
  - "Used this period" badge on models with recent activity
