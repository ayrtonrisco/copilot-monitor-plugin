package com.github.copilotmonitor.notifications

import com.github.copilotmonitor.BudgetAlertListener
import com.github.copilotmonitor.CacheHitRateAlertListener
import com.github.copilotmonitor.ContextWindowWarningEvent
import com.github.copilotmonitor.ContextWindowWarningListener
import com.github.copilotmonitor.CopilotMonitorTopics
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.APP)
class AlertNotificationService {

    private val logger = thisLogger()
    private val settings get() = com.github.copilotmonitor.settings.CopilotMonitorSettings.getInstance()
    private val lastAlertTime = ConcurrentHashMap<String, Instant>()

    fun init() {
        val bus = ApplicationManager.getApplication().messageBus.connect()

        bus.subscribe(CopilotMonitorTopics.BUDGET_ALERT, object : BudgetAlertListener {
            override fun onBudgetAlert(thresholdPct: Double, currentPct: Double, currentUsd: Double) {
                val key = "BUDGET_${thresholdPct.toInt()}"
                if (!shouldFire(key)) return
                val type = if (thresholdPct >= 100) NotificationType.ERROR else NotificationType.WARNING
                showNotification(
                    title = "Copilot Budget Alert",
                    content = "You've used ${currentPct.toInt()}% of your monthly budget (\$${"%.3f".format(currentUsd)})",
                    type = type
                )
            }
        })

        bus.subscribe(CopilotMonitorTopics.CONTEXT_WINDOW_WARNING, object : ContextWindowWarningListener {
            override fun onContextWindowWarning(event: ContextWindowWarningEvent) {
                val key = "CONTEXT_${event.warning.name}"
                if (!shouldFire(key)) return
                val type = when (event.warning) {
                    com.github.copilotmonitor.model.ContextWindowWarning.EXCEEDED -> NotificationType.ERROR
                    else -> NotificationType.WARNING
                }
                val recsText = if (event.recommendations.isNotEmpty())
                    "<br>" + event.recommendations.joinToString("<br>• ", prefix = "• ")
                else ""
                showNotification(
                    title = "Context Window Warning",
                    content = "Context utilization: ${event.utilizationPct.toInt()}% (${ event.model})$recsText",
                    type = type
                )
            }
        })

        bus.subscribe(CopilotMonitorTopics.CACHE_HIT_RATE_ALERT, object : CacheHitRateAlertListener {
            override fun onCacheHitRateLow(hitRate: Double, threshold: Double) {
                val key = "CACHE_LOW"
                if (!shouldFire(key)) return
                showNotification(
                    title = "Cache Hit Rate Low",
                    content = "Cache hit rate ${(hitRate * 100).toInt()}% is below threshold ${(threshold * 100).toInt()}%. Keep consistent files open.",
                    type = NotificationType.INFORMATION
                )
            }
        })
    }

    private fun shouldFire(key: String): Boolean {
        val cooldownMs = settings.alertCooldownMinutes * 60_000L
        val last = lastAlertTime[key]
        val now = Instant.now()
        if (last != null && now.toEpochMilli() - last.toEpochMilli() < cooldownMs) return false
        lastAlertTime[key] = now
        return true
    }

    private fun showNotification(title: String, content: String, type: NotificationType) {
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CopilotMonitor.Alerts")
                .createNotification(title, content, type)
                .notify(null)
        } catch (e: Exception) {
            logger.warn("Failed to show notification: ${e.message}")
        }
    }
}
