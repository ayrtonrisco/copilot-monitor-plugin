package com.github.copilotmonitor.toolwindow

import com.github.copilotmonitor.MetricsFacade
import com.github.copilotmonitor.services.CacheAnalysisService
import com.github.copilotmonitor.services.ContextWindowService
import com.github.copilotmonitor.services.PerformanceService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

class CopilotMonitorPanel(private val project: Project) : Disposable {

    private val _component: JComponent
    private var browser: JBCefBrowser? = null
    private var jsQuery: JBCefJSQuery? = null
    private var currentPanel = "overview"
    private val refreshScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "copilot-monitor-panel-refresh").also { it.isDaemon = true }
    }

    init {
        _component = if (JBCefApp.isSupported()) {
            createJcefPanel()
        } else {
            JLabel("JCEF not available. Please enable it in IDE settings.", SwingConstants.CENTER)
        }
    }

    val component: JComponent get() = _component

    private fun extractAndGetWebviewUrl(): String {
        val webviewDir = Paths.get(PathManager.getSystemPath(), "copilot-monitor", "webview")
        Files.createDirectories(webviewDir)
        listOf("index.html", "dashboard.js", "charts.js", "chart.umd.min.js").forEach { name ->
            javaClass.classLoader.getResourceAsStream("webview/$name")?.use { input ->
                Files.copy(input, webviewDir.resolve(name), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return webviewDir.resolve("index.html").toUri().toString()
    }

    private fun createJcefPanel(): JComponent {
        val b = JBCefBrowser()
        browser = b
        Disposer.register(this, b)

        val query = JBCefJSQuery.create(b as JBCefBrowserBase)
        jsQuery = query
        query.addHandler { request ->
            handleJsRequest(request)
            JBCefJSQuery.Response("ok")
        }

        b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    ApplicationManager.getApplication().invokeLater { pushPanelData("overview") }
                }
            }
        }, b.cefBrowser)

        b.loadURL(extractAndGetWebviewUrl())

        refreshScheduler.scheduleWithFixedDelay({
            ApplicationManager.getApplication().invokeLater { pushPanelData(currentPanel) }
        }, 30, 30, TimeUnit.SECONDS)

        val panel = JPanel(BorderLayout())
        panel.add(b.component, BorderLayout.CENTER)
        return panel
    }

    fun switchPanel(panelName: String) {
        currentPanel = panelName
        browser?.cefBrowser?.executeJavaScript(
            "window.copilotMonitor && window.copilotMonitor.switchPanel('$panelName')",
            "", 0
        )
        pushPanelData(panelName)
    }

    fun pushPanelData(panel: String = currentPanel) {
        val facade = MetricsFacade.getInstance()
        val data = when (panel) {
            "overview"    -> buildOverviewData(facade)
            "context"     -> buildContextData()
            "tokens"      -> buildTokensData(facade)
            "cache"       -> buildCacheData(facade)
            "performance" -> buildPerfData()
            "models"      -> buildModelsData(facade)
            else -> "{}"
        }
        browser?.cefBrowser?.executeJavaScript(
            "window.copilotMonitor && window.copilotMonitor.update($data)",
            "", 0
        )
    }

    private fun buildOverviewData(facade: MetricsFacade): String {
        val today = facade.getTodayStats()
        val acceptance = facade.getAcceptanceRate(30)
        val dailyCost = facade.getDailyCostUsd()
        val recent = facade.getLastInteractions(10)
        val summaries = facade.getDailySummary(7)

        return buildJsonObject {
            put("panel", "overview")
            putJsonObject("kpis") {
                put("todayTokens", today.inputTokens + today.outputTokens)
                put("todayInput", today.inputTokens)
                put("todayOutput", today.outputTokens)
                put("todayCostUsd", dailyCost)
                put("acceptanceRate", acceptance ?: 0.0)
                put("fluencyScore", facade.getFluencyScore(30))
            }
            putJsonArray("recentInteractions") {
                recent.forEach { i ->
                    add(buildJsonObject {
                        put("model", i.model)
                        put("inputTokens", i.inputTokens)
                        put("outputTokens", i.outputTokens)
                        put("featureType", i.featureType.name)
                        put("latencyMs", i.latencyMs)
                        put("timestamp", i.timestamp.toString())
                    })
                }
            }
            putJsonArray("trend") {
                summaries.forEach { s ->
                    add(buildJsonObject {
                        put("date", s.date)
                        put("inputTokens", s.totalInputTokens)
                        put("outputTokens", s.totalOutputTokens)
                        put("costUsd", s.costEstimateUsd)
                    })
                }
            }
        }.toString()
    }

    private fun buildContextData(): String {
        val contextService = project.getService(ContextWindowService::class.java)
        val status = contextService?.getCurrentStatus()
        return buildJsonObject {
            put("panel", "context")
            if (status != null) {
                put("usedTokens", status.usedTokensEstimate)
                put("maxPrompt", status.maxPromptTokens)
                put("utilizationPct", status.utilizationPct)
                put("openTabCount", status.openTabCount)
                put("currentModel", status.currentModel)
                put("warning", status.warning?.name ?: "")
                putJsonArray("recommendations") {
                    contextService.generateRecommendations(status).forEach { rec: String ->
                        add(JsonPrimitive(rec))
                    }
                }
            }
        }.toString()
    }

    private fun buildTokensData(facade: MetricsFacade): String {
        val projection = facade.getMonthlyProjection()
        val monthly = facade.getMonthlyTotal()
        val summaries = facade.getDailySummary(30)
        val modelUsage = facade.getModelUsage(30)

        return buildJsonObject {
            put("panel", "tokens")
            putJsonObject("kpis") {
                val today = facade.getTodayStats()
                put("todayInputTokens", today.inputTokens)
                put("todayOutputTokens", today.outputTokens)
                put("todayCostUsd", facade.getDailyCostUsd())
                put("monthInputTokens", monthly.inputTokens)
                put("monthOutputTokens", monthly.outputTokens)
                put("monthCostUsd", facade.getMonthlyCostUsd())
            }
            putJsonObject("projection") {
                put("daysElapsed", projection.daysElapsed)
                put("daysInMonth", projection.daysInMonth)
                put("actualToDateUsd", projection.actualToDateUsd)
                put("projectedTotalUsd", projection.projectedMonthTotalUsd)
                put("budgetUsd", projection.budgetUsd ?: 0.0)
                put("projectedOverBudgetPct", projection.projectedOverBudgetPct ?: 0.0)
            }
            putJsonArray("dailyChart") {
                summaries.forEach { s ->
                    add(buildJsonObject {
                        put("date", s.date)
                        put("inputTokens", s.totalInputTokens)
                        put("outputTokens", s.totalOutputTokens)
                        put("cacheReadTokens", s.totalCacheReadTokens)
                        put("costUsd", s.costEstimateUsd)
                    })
                }
            }
            putJsonArray("modelBreakdown") {
                modelUsage.forEach { m ->
                    add(buildJsonObject {
                        put("model", m.modelId)
                        put("displayName", m.displayName)
                        put("tokens", m.inputTokens + m.outputTokens)
                        put("costUsd", m.totalCostUsd)
                        put("pct", m.pct)
                    })
                }
            }
            putJsonObject("costBreakdown") {
                put("inputCostUsd", facade.getDailyInputCostUsd())
                put("outputCostUsd", facade.getDailyOutputCostUsd())
                put("cacheReadCostUsd", facade.getDailyCacheReadCostUsd())
            }
        }.toString()
    }

    private fun buildCacheData(facade: MetricsFacade): String {
        val cacheService: CacheAnalysisService = service()
        val stats = facade.getCacheStats(7)
        val trend = cacheService.getHitRateTrend(30)
        return buildJsonObject {
            put("panel", "cache")
            put("cacheHitRate", stats.cacheHitRate)
            put("cacheCreationRate", stats.cacheCreationRate)
            put("totalCacheReadTokens", stats.totalCacheReadTokens)
            put("totalCacheCreationTokens", stats.totalCacheCreationTokens)
            put("estimatedSavingsUsd", stats.estimatedSavingsUsd)
            putJsonArray("trend") {
                trend.forEach { t ->
                    add(buildJsonObject { put("date", t.date); put("hitRate", t.hitRate) })
                }
            }
            putJsonArray("recommendations") {
                cacheService.generateRecommendations(stats).forEach { r ->
                    add(buildJsonObject {
                        put("priority", r.priority.name)
                        put("message", r.message)
                        put("action", r.action ?: "")
                    })
                }
            }
        }.toString()
    }

    private fun buildPerfData(): String {
        val perfService: PerformanceService = service()
        val latency = perfService.getLatencyStats(7)
        val latency30d = perfService.getLatencyStats(30)
        val ttft = perfService.getTtftStats(7)
        val errorRate = perfService.getErrorRate(7)
        val finishReasons = perfService.getFinishReasonDistribution(7)
        val histogram = perfService.getLatencyHistogram(7)
        val signals = perfService.detectDegradation()

        return buildJsonObject {
            put("panel", "performance")
            putJsonObject("latency") {
                putJsonObject("7d") {
                    put("p50", latency.p50Ms); put("p90", latency.p90Ms)
                    put("p99", latency.p99Ms); put("avg", latency.avgMs)
                }
                putJsonObject("30d") {
                    put("p50", latency30d.p50Ms); put("p90", latency30d.p90Ms)
                    put("avg", latency30d.avgMs)
                }
            }
            putJsonObject("ttft") {
                put("p50", ttft.p50Ms); put("p90", ttft.p90Ms); put("avg", ttft.avgMs)
            }
            put("errorRate", errorRate)
            putJsonObject("finishReasons") {
                finishReasons.forEach { (k, v) -> put(k.name, v) }
            }
            putJsonArray("histogram") {
                histogram.forEach { b ->
                    add(buildJsonObject { put("bucket", b.bucket); put("count", b.count) })
                }
            }
            putJsonArray("degradationSignals") {
                signals.forEach { s ->
                    add(buildJsonObject {
                        put("type", s.type.name)
                        put("evidence", s.evidence)
                    })
                }
            }
        }.toString()
    }

    private fun buildModelsData(facade: MetricsFacade): String {
        val comparison = facade.getModelComparison()
        val usage = facade.getModelUsage(30)
        return buildJsonObject {
            put("panel", "models")
            putJsonArray("usage") {
                usage.forEach { m ->
                    add(buildJsonObject {
                        put("modelId", m.modelId); put("displayName", m.displayName)
                        put("inputTokens", m.inputTokens); put("outputTokens", m.outputTokens)
                        put("pct", m.pct); put("avgAcceptanceRate", m.avgAcceptanceRate ?: 0.0)
                        put("avgLatencyMs", m.avgLatencyMs); put("totalCostUsd", m.totalCostUsd)
                    })
                }
            }
            putJsonArray("comparison") {
                comparison.forEach { c ->
                    add(buildJsonObject {
                        put("modelId", c.modelId); put("contextWindow", c.contextWindow)
                        put("maxPrompt", c.maxPrompt); put("maxPromptUtilizationPct", c.maxPromptUtilizationPct)
                        put("premiumMultiplier", c.premiumMultiplier); put("avgTtftMs", c.avgTtftMs)
                        put("avgAcceptanceRate", c.avgAcceptanceRate ?: 0.0)
                        put("costPer1kInputUsd", c.costPer1kInputUsd)
                        put("supportsCache", c.supportsCache); put("usedThisPeriod", c.usedThisPeriod)
                    })
                }
            }
        }.toString()
    }

    private fun handleJsRequest(request: String) {
        try {
            val obj = Json.parseToJsonElement(request) as? JsonObject ?: return
            val action = (obj["action"] as? JsonPrimitive)?.content ?: return
            when (action) {
                "openSettings" -> com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, "Copilot Monitor")
                "switchPanel"  -> (obj["panel"] as? JsonPrimitive)?.content?.let { switchPanel(it) }
                "refreshData"  -> pushPanelData(currentPanel)
            }
        } catch (_: Exception) {}
    }

    override fun dispose() {
        refreshScheduler.shutdown()
    }
}
