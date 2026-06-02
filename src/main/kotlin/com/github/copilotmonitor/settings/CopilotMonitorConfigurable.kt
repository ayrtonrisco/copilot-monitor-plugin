package com.github.copilotmonitor.settings

import com.intellij.openapi.options.Configurable
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.border.TitledBorder

class CopilotMonitorConfigurable : Configurable {

    private var mainPanel: JPanel? = null

    private val showStatusBarCb = JCheckBox("Show token usage in status bar")
    private val budgetUsdField = JTextField(10)
    private val tokenBudgetField = JTextField(10)
    private val alertCooldownField = JTextField(5)

    private val showContextWarningsCb = JCheckBox("Show context window warning notifications")
    private val contextWarningThresholdField = JTextField(5)
    private val contextCriticalThresholdField = JTextField(5)

    private val showCacheRecsCb = JCheckBox("Show cache recommendations")
    private val cacheThresholdField = JTextField(5)

    private val otelEnabledCb = JCheckBox("Enable OpenTelemetry export")
    private val otelEndpointField = JTextField(30)
    private val otelUseGrpcCb = JCheckBox("Use gRPC (uncheck for HTTP)")
    private val otelIncludeContentCb = JCheckBox("Include content (prompts/responses) — PRIVACY RISK")

    private val retentionField = JTextField(5)

    override fun getDisplayName() = "Copilot Monitor"

    override fun createComponent(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        // General section
        val generalPanel = JPanel()
        generalPanel.layout = BoxLayout(generalPanel, BoxLayout.Y_AXIS)
        generalPanel.border = TitledBorder("General")
        generalPanel.add(showStatusBarCb)
        generalPanel.add(row("Monthly cost budget ($):", budgetUsdField))
        generalPanel.add(row("Monthly token budget:", tokenBudgetField))
        generalPanel.add(row("Alert cooldown (minutes):", alertCooldownField))
        panel.add(generalPanel)

        // Context window section
        val contextPanel = JPanel()
        contextPanel.layout = BoxLayout(contextPanel, BoxLayout.Y_AXIS)
        contextPanel.border = TitledBorder("Context Window")
        contextPanel.add(showContextWarningsCb)
        contextPanel.add(row("Warning threshold (%):", contextWarningThresholdField))
        contextPanel.add(row("Critical threshold (%):", contextCriticalThresholdField))
        panel.add(contextPanel)

        // Cache section
        val cachePanel = JPanel()
        cachePanel.layout = BoxLayout(cachePanel, BoxLayout.Y_AXIS)
        cachePanel.border = TitledBorder("Cache")
        cachePanel.add(showCacheRecsCb)
        cachePanel.add(row("Alert when hit rate below (%):", cacheThresholdField))
        panel.add(cachePanel)

        // OTel section
        val otelPanel = JPanel()
        otelPanel.layout = BoxLayout(otelPanel, BoxLayout.Y_AXIS)
        otelPanel.border = TitledBorder("Export (Opt-in)")
        otelPanel.add(otelEnabledCb)
        otelPanel.add(row("OTLP endpoint:", otelEndpointField))
        otelPanel.add(otelUseGrpcCb)
        otelPanel.add(otelIncludeContentCb)
        panel.add(otelPanel)

        // Data section
        val dataPanel = JPanel()
        dataPanel.layout = BoxLayout(dataPanel, BoxLayout.Y_AXIS)
        dataPanel.border = TitledBorder("Data")
        dataPanel.add(row("Data retention (days):", retentionField))
        panel.add(dataPanel)

        mainPanel = panel
        reset()
        return panel
    }

    private fun row(label: String, field: JComponent): JPanel {
        val p = JPanel()
        p.add(JLabel(label))
        p.add(field)
        return p
    }

    override fun isModified(): Boolean {
        val s = CopilotMonitorSettings.getInstance()
        return showStatusBarCb.isSelected != s.showStatusBar
            || budgetUsdField.text != s.monthlyBudgetUsd.toString()
            || tokenBudgetField.text != s.monthlyTokenBudget.toString()
            || alertCooldownField.text != s.alertCooldownMinutes.toString()
            || showContextWarningsCb.isSelected != s.showContextWindowWarnings
            || contextWarningThresholdField.text != s.contextWindowWarningThreshold.toString()
            || contextCriticalThresholdField.text != s.contextWindowCriticalThreshold.toString()
            || showCacheRecsCb.isSelected != s.showCacheRecommendations
            || cacheThresholdField.text != (s.cacheHitRateLowThreshold * 100).toInt().toString()
            || otelEnabledCb.isSelected != s.otelEnabled
            || otelEndpointField.text != s.otelEndpoint
            || otelUseGrpcCb.isSelected != s.otelUseGrpc
            || otelIncludeContentCb.isSelected != s.otelIncludeContent
            || retentionField.text != s.dataRetentionDays.toString()
    }

    override fun apply() {
        val s = CopilotMonitorSettings.getInstance()
        s.showStatusBar = showStatusBarCb.isSelected
        s.monthlyBudgetUsd = budgetUsdField.text.toDoubleOrNull() ?: 0.0
        s.monthlyTokenBudget = tokenBudgetField.text.toLongOrNull() ?: 0L
        s.alertCooldownMinutes = alertCooldownField.text.toIntOrNull() ?: 30
        s.showContextWindowWarnings = showContextWarningsCb.isSelected
        s.contextWindowWarningThreshold = contextWarningThresholdField.text.toIntOrNull() ?: 50
        s.contextWindowCriticalThreshold = contextCriticalThresholdField.text.toIntOrNull() ?: 80
        s.showCacheRecommendations = showCacheRecsCb.isSelected
        s.cacheHitRateLowThreshold = (cacheThresholdField.text.toIntOrNull() ?: 30) / 100.0
        s.otelEnabled = otelEnabledCb.isSelected
        s.otelEndpoint = otelEndpointField.text.trim()
        s.otelUseGrpc = otelUseGrpcCb.isSelected
        s.otelIncludeContent = otelIncludeContentCb.isSelected
        s.dataRetentionDays = retentionField.text.toIntOrNull() ?: 90
    }

    override fun reset() {
        val s = CopilotMonitorSettings.getInstance()
        showStatusBarCb.isSelected = s.showStatusBar
        budgetUsdField.text = s.monthlyBudgetUsd.toString()
        tokenBudgetField.text = s.monthlyTokenBudget.toString()
        alertCooldownField.text = s.alertCooldownMinutes.toString()
        showContextWarningsCb.isSelected = s.showContextWindowWarnings
        contextWarningThresholdField.text = s.contextWindowWarningThreshold.toString()
        contextCriticalThresholdField.text = s.contextWindowCriticalThreshold.toString()
        showCacheRecsCb.isSelected = s.showCacheRecommendations
        cacheThresholdField.text = (s.cacheHitRateLowThreshold * 100).toInt().toString()
        otelEnabledCb.isSelected = s.otelEnabled
        otelEndpointField.text = s.otelEndpoint
        otelUseGrpcCb.isSelected = s.otelUseGrpc
        otelIncludeContentCb.isSelected = s.otelIncludeContent
        retentionField.text = s.dataRetentionDays.toString()
    }
}
