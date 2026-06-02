# Module: Token Consumption & Cost Estimation

## Purpose

Track token usage across all Copilot features and estimate financial cost against
a configurable monthly budget. Critical since GitHub Copilot migrated to usage-based
billing in June 2026.

## Billing Model (June 2026+)

GitHub Copilot now uses **usage-based billing**:
- Standard completions and chat: included in base plan
- Premium requests (premium models): charged at model-specific multiplier
- Session limits and weekly limits apply to Pro/Pro+ plans (since April 2026)

## Implementation Tasks

- [ ] **Task TC-1:** `CostEstimationService`
  ```kotlin
  class CostEstimationService(
      private val storage: MetricsStorageService,
      private val modelRepo: ModelConfigRepository,
      private val settings: CopilotMonitorSettings
  ) : ApplicationService {
      
      fun estimateForInteraction(interaction: Interaction): Double {
          val config = modelRepo.get(interaction.model)
          val inputCost = (interaction.inputTokens - interaction.cacheReadTokens) / 1000.0 * config.costPer1kInputUsd
          val cacheCost = interaction.cacheReadTokens / 1000.0 * config.costPer1kCacheReadUsd
          val outputCost = interaction.outputTokens / 1000.0 * config.costPer1kOutputUsd
          return (inputCost + cacheCost + outputCost) * config.premiumMultiplier
      }
      
      fun getMonthlyTotal(): Double
      fun getDailyTotal(): Double
      fun getProjection(): MonthlyProjection  // based on rolling 7-day average
  }
  ```

- [ ] **Task TC-2:** `MonthlyProjection`
  ```kotlin
  data class MonthlyProjection(
      val daysElapsed: Int,
      val daysInMonth: Int,
      val actualToDateUsd: Double,
      val projectedMonthTotalUsd: Double,
      val projectedTokens: Long,
      val budgetUsd: Double?,
      val projectedOverBudgetPct: Double?  // null if no budget set
  )
  ```

- [ ] **Task TC-3:** Budget alert system
  - Store `monthlyBudgetUsd` in `CopilotMonitorSettings`
  - Check on every `InteractionEvent`:
    ```kotlin
    val pct = currentMonthlyTotal / settings.monthlyBudgetUsd * 100
    for (threshold in listOf(75.0, 90.0, 100.0)) {
        if (pct >= threshold && !alertAlreadyFiredThisMonth(threshold)) {
            fireBudgetAlert(threshold, pct)
        }
    }
    ```
  - Track fired alerts in `alerts` table with `type = "BUDGET_${threshold.toInt()}"`

- [ ] **Task TC-4:** Session limit tracking
  - Parse session limit messages from Copilot logs: `"approaching your session limit"` etc.
  - Track weekly limit usage
  - Show in status bar tooltip when approaching limit

- [ ] **Task TC-5:** Tokens panel webview data
  ```json
  {
    "kpis": {
      "todayInputTokens": 12400,
      "todayOutputTokens": 3200,
      "todayCostUsd": 0.031,
      "sessionInputTokens": 3100,
      "monthInputTokens": 284000,
      "monthCostUsd": 0.71
    },
    "budget": {
      "monthlyBudgetUsd": 5.00,
      "usedPct": 14.2,
      "projectedTotalUsd": 3.12,
      "daysElapsed": 2,
      "daysInMonth": 30
    },
    "dailyChart": [
      { "date": "2026-05-27", "inputTokens": 18000, "outputTokens": 4200, "cacheReadTokens": 9000, "costUsd": 0.045 }
    ],
    "modelBreakdown": [
      { "model": "gpt-4o", "tokens": 142000, "costUsd": 0.35, "pct": 50 },
      { "model": "claude-sonnet-4-20250514", "tokens": 84000, "costUsd": 0.25, "pct": 29 }
    ],
    "featureBreakdown": [
      { "type": "CHAT_ASK", "tokens": 124000, "pct": 44 },
      { "type": "COMPLETION_INLINE", "tokens": 98000, "pct": 34 },
      { "type": "CHAT_AGENT", "tokens": 64000, "pct": 22 }
    ]
  }
  ```

## Status Bar Format

| State               | Display                          |
|---------------------|----------------------------------|
| Normal              | `🤖 ~12.4K` (green)             |
| >70% budget         | `🤖 ~12.4K ⚠` (yellow)         |
| >90% budget         | `🤖 ~12.4K 🔴` (red)           |
| Session limit close | `🤖 ~12.4K ⏱` (orange)         |
| No data yet         | `🤖 —`                          |
