package com.github.copilotmonitor.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil

@Service
@State(name = "CopilotMonitorSettings", storages = [Storage("copilotMonitorSettings.xml")])
class CopilotMonitorSettings : PersistentStateComponent<CopilotMonitorSettings> {

    var showStatusBar: Boolean = true
    var monthlyTokenBudget: Long = 0L
    var monthlyBudgetUsd: Double = 0.0
    var alertAt75: Boolean = true
    var alertAt90: Boolean = true
    var alertAt100: Boolean = true
    var alertCooldownMinutes: Int = 30

    var showContextWindowWarnings: Boolean = true
    var contextWindowWarningThreshold: Int = 50
    var contextWindowCriticalThreshold: Int = 80

    var showCacheRecommendations: Boolean = true
    var cacheHitRateLowThreshold: Double = 0.30

    var otelEnabled: Boolean = false
    var otelEndpoint: String = "http://localhost:4317"
    var otelUseGrpc: Boolean = true
    var otelIncludeContent: Boolean = false

    var dataRetentionDays: Int = 90

    override fun getState(): CopilotMonitorSettings = this

    override fun loadState(state: CopilotMonitorSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(): CopilotMonitorSettings = service()
    }
}
