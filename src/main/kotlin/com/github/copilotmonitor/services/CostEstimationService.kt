package com.github.copilotmonitor.services

import com.github.copilotmonitor.CopilotMonitorTopics
import com.github.copilotmonitor.model.Interaction
import com.github.copilotmonitor.model.MonthlyProjection
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import java.time.LocalDate
import java.time.YearMonth

@Service(Service.Level.APP)
open class CostEstimationService {

    private val logger = thisLogger()
    private val storage: MetricsStorageService by lazy { service() }
    private val settings get() = com.github.copilotmonitor.settings.CopilotMonitorSettings.getInstance()

    protected open fun getModelRepo(): ModelConfigRepository = service()

    fun estimateForInteraction(interaction: Interaction): Double {
        val config = getModelRepo().get(interaction.model)
        val freshInput = interaction.inputTokens - interaction.cacheReadTokens
        val inputCost = (freshInput / 1000.0) * config.costPer1kInputUsd
        val cacheCost = (interaction.cacheReadTokens / 1000.0) * config.costPer1kCacheReadUsd
        val outputCost = (interaction.outputTokens / 1000.0) * config.costPer1kOutputUsd
        return (inputCost + cacheCost + outputCost) * config.premiumMultiplier
    }

    fun getDailyTotal(): Double {
        val interactions = storage.getInteractionsForPeriod(1)
        return interactions.sumOf { estimateForInteraction(it) }
    }

    fun getMonthlyTotal(): Double {
        val now = LocalDate.now()
        val daysElapsed = now.dayOfMonth
        val interactions = storage.getInteractionsForPeriod(daysElapsed)
        return interactions.sumOf { estimateForInteraction(it) }
    }

    fun getProjection(): MonthlyProjection {
        val now = LocalDate.now()
        val yearMonth = YearMonth.of(now.year, now.month)
        val daysInMonth = yearMonth.lengthOfMonth()
        val daysElapsed = now.dayOfMonth

        val monthlyTotal = getMonthlyTotal()
        val dailyAvg7d = if (daysElapsed >= 1) {
            storage.getInteractionsForPeriod(7).sumOf { estimateForInteraction(it) } / minOf(7, daysElapsed)
        } else 0.0

        val remainingDays = daysInMonth - daysElapsed
        val projected = monthlyTotal + (dailyAvg7d * remainingDays)

        val monthlyTokens = storage.getMonthlyTotal()
        val dailyTokenAvg = if (daysElapsed > 0) monthlyTokens.inputTokens / daysElapsed else 0L
        val projectedTokens = monthlyTokens.inputTokens + (dailyTokenAvg * remainingDays)

        val budgetUsd = if (settings.monthlyBudgetUsd > 0) settings.monthlyBudgetUsd else null
        val overBudgetPct = budgetUsd?.let {
            if (it > 0) ((projected - it) / it * 100).coerceAtLeast(0.0) else null
        }

        return MonthlyProjection(
            daysElapsed = daysElapsed,
            daysInMonth = daysInMonth,
            actualToDateUsd = monthlyTotal,
            projectedMonthTotalUsd = projected,
            projectedTokens = projectedTokens,
            budgetUsd = budgetUsd,
            projectedOverBudgetPct = overBudgetPct
        )
    }

    fun checkBudgetAlerts() {
        val budgetUsd = settings.monthlyBudgetUsd
        if (budgetUsd <= 0) return

        val currentTotal = getMonthlyTotal()
        val pct = currentTotal / budgetUsd * 100

        for (threshold in listOf(75.0, 90.0, 100.0)) {
            if (pct >= threshold && !storage.isAlertFiredThisMonth("BUDGET_${threshold.toInt()}")) {
                storage.recordAlert("BUDGET_${threshold.toInt()}", "WARNING",
                    "Budget ${threshold.toInt()}%: used $${"%.3f".format(currentTotal)} of $$budgetUsd")
                ApplicationManager.getApplication().messageBus
                    .syncPublisher(CopilotMonitorTopics.BUDGET_ALERT)
                    .onBudgetAlert(threshold, pct, currentTotal)
            }
        }
    }
}
