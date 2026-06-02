package com.github.copilotmonitor.statusbar

import com.github.copilotmonitor.CopilotMonitorTopics
import com.github.copilotmonitor.InteractionListener
import com.github.copilotmonitor.MetricsFacade
import com.github.copilotmonitor.model.Interaction
import com.github.copilotmonitor.settings.CopilotMonitorSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TokenUsageStatusBarWidget(project: Project) :
    EditorBasedWidget(project), StatusBarWidget.TextPresentation {

    companion object {
        const val ID = "CopilotMonitor.TokenUsage"
    }

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "copilot-monitor-status-bar").also { it.isDaemon = true }
    }

    @Volatile private var displayText: String = "🤖 —"
    @Volatile private var tooltipText: String = "Copilot Monitor"

    override fun ID() = ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun getText(): String = displayText
    override fun getTooltipText(): String = tooltipText
    override fun getAlignment() = 0f

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        ApplicationManager.getApplication().invokeLater {
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("Copilot Monitor")?.show()
        }
    }

    override fun install(statusBar: StatusBar) {
        super.install(statusBar)
        subscribeToInteractions()
        startRefresh()
    }

    private fun subscribeToInteractions() {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(CopilotMonitorTopics.INTERACTION_EVENT, object : InteractionListener {
                override fun onInteraction(interaction: Interaction) {
                    refresh()
                }
            })
    }

    private fun startRefresh() {
        executor.scheduleWithFixedDelay({ refresh() }, 5, 30, TimeUnit.SECONDS)
    }

    private fun refresh() {
        if (!CopilotMonitorSettings.getInstance().showStatusBar) {
            ApplicationManager.getApplication().invokeLater {
                displayText = ""
                myStatusBar?.updateWidget(ID())
            }
            return
        }

        try {
            val facade = MetricsFacade.getInstance()
            val today = facade.getTodayStats()
            val totalTokens = today.inputTokens + today.outputTokens
            val dailyCost = facade.getDailyCostUsd()
            val settings = CopilotMonitorSettings.getInstance()

            val formattedTokens = formatTokenCount(totalTokens)
            val budgetPct = if (settings.monthlyBudgetUsd > 0) {
                facade.getMonthlyCostUsd() / settings.monthlyBudgetUsd * 100
            } else 0.0

            val icon = "🤖"
            displayText = when {
                budgetPct > 90 -> "$icon ~$formattedTokens 🔴"
                budgetPct > 70 -> "$icon ~$formattedTokens ⚠"
                else -> "$icon ~$formattedTokens"
            }

            val monthly = facade.getMonthlyTotal()
            val sessionStats = "Session: ~${formatTokenCount(today.inputTokens + today.outputTokens)} tokens"

            tooltipText = buildString {
                appendLine("Copilot Monitor")
                appendLine("Today: ~$formattedTokens tokens  (\$${"%.3f".format(dailyCost)})")
                appendLine(sessionStats)
                appendLine("Monthly: ~${formatTokenCount(monthly.inputTokens + monthly.outputTokens)} tokens")
                append("Click to open dashboard")
            }

            ApplicationManager.getApplication().invokeLater {
                myStatusBar?.updateWidget(ID())
            }
        } catch (e: Exception) {
            displayText = "🤖 —"
        }
    }

    private fun formatTokenCount(tokens: Long): String = when {
        tokens >= 1_000_000 -> "${"%.1f".format(tokens / 1_000_000.0)}M"
        tokens >= 1_000 -> "${"%.1f".format(tokens / 1_000.0)}K"
        else -> tokens.toString()
    }

    override fun dispose() {
        executor.shutdown()
        super.dispose()
    }
}
