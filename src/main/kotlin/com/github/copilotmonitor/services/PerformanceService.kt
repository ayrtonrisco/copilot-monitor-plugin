package com.github.copilotmonitor.services

import com.github.copilotmonitor.model.DegradationSignal
import com.github.copilotmonitor.model.DegradationType
import com.github.copilotmonitor.model.FinishReason
import com.github.copilotmonitor.model.Interaction
import com.github.copilotmonitor.model.LatencyStats
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import java.time.Instant

@Service(Service.Level.APP)
class PerformanceService {

    private val logger = thisLogger()
    private val storage: MetricsStorageService by lazy { service() }

    fun getLatencyStats(days: Int, model: String? = null): LatencyStats {
        val interactions = storage.getInteractionsForPeriod(days)
            .filter { it.latencyMs >= 0 }
            .let { if (model != null) it.filter { i -> i.model == model } else it }

        return computeLatencyStats(interactions.map { it.latencyMs })
    }

    fun getTtftStats(days: Int): LatencyStats {
        val values = storage.getInteractionsForPeriod(days)
            .filter { it.ttftMs >= 0 }
            .map { it.ttftMs }

        return computeLatencyStats(values)
    }

    fun getErrorRate(days: Int): Double {
        val interactions = storage.getInteractionsForPeriod(days)
        if (interactions.isEmpty()) return 0.0
        val errors = interactions.count { it.finishReason == FinishReason.ERROR }
        return errors.toDouble() / interactions.size
    }

    fun getFinishReasonDistribution(days: Int): Map<FinishReason, Int> {
        return storage.getInteractionsForPeriod(days)
            .groupBy { it.finishReason }
            .mapValues { (_, v) -> v.size }
    }

    fun detectDegradation(): List<DegradationSignal> {
        val recent = storage.getInteractionsForPeriod(7)
        val signals = mutableListOf<DegradationSignal>()

        // Response truncation detection
        val totalRecent = recent.size
        if (totalRecent > 20) {
            val lengthCount = recent.count { it.finishReason == FinishReason.LENGTH }
            val lengthRate = lengthCount.toDouble() / totalRecent
            if (lengthRate > 0.05) {
                signals.add(DegradationSignal(
                    type = DegradationType.RESPONSE_TRUNCATION,
                    contextUtilizationPct = 0.0,
                    evidence = "${(lengthRate * 100).toInt()}% of responses truncated (LENGTH finish reason)",
                    timestamp = Instant.now()
                ))
            }
        }

        // Latency spike detection
        val latencies = recent.filter { it.latencyMs >= 0 }.map { it.latencyMs }
        if (latencies.size > 10) {
            val avg = latencies.average()
            val recent10 = latencies.takeLast(10).average()
            if (recent10 > avg * 3) {
                signals.add(DegradationSignal(
                    type = DegradationType.LATENCY_SPIKE,
                    contextUtilizationPct = 0.0,
                    evidence = "Recent latency ${recent10.toLong()}ms is 3x the average ${avg.toLong()}ms",
                    timestamp = Instant.now()
                ))
            }
        }

        return signals
    }

    fun getLatencyHistogram(days: Int): List<HistogramBucket> {
        val latencies = storage.getInteractionsForPeriod(days)
            .filter { it.latencyMs >= 0 }
            .map { it.latencyMs }

        val buckets = listOf(
            HistogramBucket("<200ms", 0),
            HistogramBucket("200-500ms", 0),
            HistogramBucket("500ms-1s", 0),
            HistogramBucket("1-2s", 0),
            HistogramBucket("2-5s", 0),
            HistogramBucket(">5s", 0)
        )

        val counts = IntArray(6)
        latencies.forEach { ms ->
            val idx = when {
                ms < 200   -> 0
                ms < 500   -> 1
                ms < 1000  -> 2
                ms < 2000  -> 3
                ms < 5000  -> 4
                else       -> 5
            }
            counts[idx]++
        }

        return buckets.mapIndexed { i, b -> b.copy(count = counts[i]) }
    }

    private fun computeLatencyStats(values: List<Long>): LatencyStats {
        if (values.isEmpty()) return LatencyStats(0, 0, 0, 0.0, 0, 0, 0)
        val sorted = values.sorted()
        val size = sorted.size
        return LatencyStats(
            p50Ms = sorted[size / 2],
            p90Ms = sorted[(size * 0.9).toInt().coerceAtMost(size - 1)],
            p99Ms = sorted[(size * 0.99).toInt().coerceAtMost(size - 1)],
            avgMs = sorted.average(),
            minMs = sorted.first(),
            maxMs = sorted.last(),
            sampleCount = size
        )
    }

    data class HistogramBucket(val bucket: String, val count: Int)
}
