package com.github.copilotmonitor

import com.github.copilotmonitor.model.CacheStats
import com.github.copilotmonitor.model.ContextWindowStatus
import com.github.copilotmonitor.model.DailySummary
import com.github.copilotmonitor.model.DegradationSignal
import com.github.copilotmonitor.model.Interaction
import com.github.copilotmonitor.model.LatencyStats
import com.github.copilotmonitor.model.ModelComparison
import com.github.copilotmonitor.model.ModelUsageStat
import com.github.copilotmonitor.model.MonthlyProjection
import com.github.copilotmonitor.services.CacheAnalysisService
import com.github.copilotmonitor.services.CostEstimationService
import com.github.copilotmonitor.services.MetricsStorageService
import com.github.copilotmonitor.services.ModelAnalysisService
import com.github.copilotmonitor.services.ModelConfigRepository
import com.github.copilotmonitor.services.PerformanceService
import com.github.copilotmonitor.services.ProductivityService
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.APP)
class MetricsFacade {

    private val storage: MetricsStorageService by lazy { service() }
    private val costService: CostEstimationService by lazy { service() }
    private val cacheService: CacheAnalysisService by lazy { service() }
    private val perfService: PerformanceService by lazy { service() }
    private val productivityService: ProductivityService by lazy { service() }
    private val modelAnalysis: ModelAnalysisService by lazy { service() }

    fun getTodayStats() = storage.getTodayStats()
    fun getMonthlyTotal() = storage.getMonthlyTotal()
    fun getDailySummary(days: Int = 7): List<DailySummary> = storage.getDailySummary(days)
    fun getLastInteractions(n: Int = 10): List<Interaction> = storage.getLastNInteractions(n)

    fun getDailyCostUsd() = costService.getDailyTotal()
    fun getMonthlyCostUsd() = costService.getMonthlyTotal()
    fun getMonthlyProjection(): MonthlyProjection = costService.getProjection()
    fun getDailyInputCostUsd() = costService.getDailyInputCostUsd()
    fun getDailyOutputCostUsd() = costService.getDailyOutputCostUsd()
    fun getDailyCacheReadCostUsd() = costService.getDailyCacheReadCostUsd()

    fun getCacheStats(days: Int = 7): CacheStats = cacheService.getStats(days)

    fun getLatencyStats(days: Int = 7): LatencyStats = perfService.getLatencyStats(days)
    fun getDegradationSignals(): List<DegradationSignal> = perfService.detectDegradation()

    fun getAcceptanceRate(days: Int = 30) = productivityService.getAcceptanceRate(days)
    fun getFluencyScore(days: Int = 30) = productivityService.computeFluencyScore(days)

    fun getModelUsage(days: Int = 30): List<ModelUsageStat> = modelAnalysis.getModelUsageDistribution(days)
    fun getModelComparison(): List<ModelComparison> = modelAnalysis.getModelComparison()

    companion object {
        fun getInstance(): MetricsFacade = service()
    }
}
