package com.github.copilotmonitor.model

import java.time.Instant

data class LatencyStats(
    val p50Ms: Long,
    val p90Ms: Long,
    val p99Ms: Long,
    val avgMs: Double,
    val minMs: Long,
    val maxMs: Long,
    val sampleCount: Int
)

enum class DegradationType {
    HIGH_CONTEXT_LOW_ACCEPTANCE,
    RESPONSE_TRUNCATION,
    TOOL_CALL_LOOP,
    LATENCY_SPIKE
}

data class DegradationSignal(
    val type: DegradationType,
    val contextUtilizationPct: Double,
    val evidence: String,
    val timestamp: Instant
)
