package com.github.copilotmonitor

import com.github.copilotmonitor.model.ContextWindowWarning
import com.github.copilotmonitor.model.Interaction
import com.intellij.util.messages.Topic

interface InteractionListener {
    fun onInteraction(interaction: Interaction)
}

interface BudgetAlertListener {
    fun onBudgetAlert(thresholdPct: Double, currentPct: Double, currentUsd: Double)
}

data class ContextWindowWarningEvent(
    val warning: ContextWindowWarning,
    val utilizationPct: Double,
    val model: String,
    val recommendations: List<String>
)

interface ContextWindowWarningListener {
    fun onContextWindowWarning(event: ContextWindowWarningEvent)
}

interface CacheHitRateAlertListener {
    fun onCacheHitRateLow(hitRate: Double, threshold: Double)
}

object CopilotMonitorTopics {
    val INTERACTION_EVENT: Topic<InteractionListener> = Topic.create(
        "CopilotMonitor.InteractionEvent", InteractionListener::class.java
    )
    val BUDGET_ALERT: Topic<BudgetAlertListener> = Topic.create(
        "CopilotMonitor.BudgetAlert", BudgetAlertListener::class.java
    )
    val CONTEXT_WINDOW_WARNING: Topic<ContextWindowWarningListener> = Topic.create(
        "CopilotMonitor.ContextWindowWarning", ContextWindowWarningListener::class.java
    )
    val CACHE_HIT_RATE_ALERT: Topic<CacheHitRateAlertListener> = Topic.create(
        "CopilotMonitor.CacheHitRateAlert", CacheHitRateAlertListener::class.java
    )
}
