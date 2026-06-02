package com.github.copilotmonitor.services

import com.github.copilotmonitor.CopilotMonitorTopics
import com.github.copilotmonitor.model.FeatureType
import com.github.copilotmonitor.model.FinishReason
import com.github.copilotmonitor.model.Interaction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class IdeaLogWatcher {

    private val logger = thisLogger()
    private val storage: MetricsStorageService by lazy { service() }

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "copilot-monitor-idea-log-watcher").also { it.isDaemon = true }
    }

    private val logPosition = AtomicLong(0L)
    private val logPath: Path by lazy {
        Paths.get(PathManager.getLogPath(), "idea.log")
    }

    // Regex for: INFO - #copilot - [fetchChat] Request ... finished with 200 status after 1488.97ms
    private val requestRegex = Regex("""#copilot.*\[(\w+)].*finished with (\d+) status after ([\d.]+)ms""")

    // Regex for: INFO - #copilot - [streamMessages] message 0 returned. finish reason: [stop]
    private val finishRegex = Regex("""#copilot.*finish reason: \[(\w+)]""")

    // Regex for timestamp at start of log line: 2026-06-02 15:50:28,709
    private val tsRegex = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})""")

    fun start() {
        if (!Files.exists(logPath)) {
            logger.info("idea.log not found at $logPath — will retry on next poll")
        }
        // Seek to end of current file so we only pick up new entries
        try {
            logPosition.set(Files.size(logPath))
        } catch (_: Exception) {
            logPosition.set(0L)
        }
        scheduler.scheduleWithFixedDelay({ poll() }, 10, 10, TimeUnit.SECONDS)
    }

    fun stop() {
        scheduler.shutdown()
    }

    private fun poll() {
        try {
            if (!Files.exists(logPath)) return
            val fileSize = Files.size(logPath)
            if (fileSize < logPosition.get()) {
                // Log was rotated
                logPosition.set(0L)
            }
            if (fileSize == logPosition.get()) return

            val newLines = readNewLines(logPath, logPosition.get())
            logPosition.set(fileSize)
            processLines(newLines)
        } catch (e: Exception) {
            logger.debug("idea.log poll error: ${e.message}")
        }
    }

    private fun readNewLines(path: Path, fromOffset: Long): List<String> {
        val lines = mutableListOf<String>()
        RandomAccessFile(path.toFile(), "r").use { raf ->
            raf.seek(fromOffset)
            var line = raf.readLine()
            while (line != null) {
                lines.add(line)
                line = raf.readLine()
            }
        }
        return lines
    }

    private fun processLines(lines: List<String>) {
        var pendingOperation: String? = null
        var pendingStatus: Int = 200
        var pendingLatencyMs: Long = -1
        var pendingTimestamp: Instant = Instant.now()

        for (line in lines) {
            val reqMatch = requestRegex.find(line)
            if (reqMatch != null) {
                val operation = reqMatch.groupValues[1]
                val status = reqMatch.groupValues[2].toIntOrNull() ?: 200
                val latencyMs = reqMatch.groupValues[3].toDoubleOrNull()?.toLong() ?: -1L
                val ts = tsRegex.find(line)?.groupValues?.get(1)
                pendingTimestamp = parseTimestamp(ts)
                pendingOperation = operation
                pendingStatus = status
                pendingLatencyMs = latencyMs
                continue
            }

            val finMatch = finishRegex.find(line)
            if (finMatch != null && pendingOperation != null) {
                val finishReasonStr = finMatch.groupValues[1].lowercase()
                val finishReason = when (finishReasonStr) {
                    "stop"       -> FinishReason.STOP
                    "length"     -> FinishReason.LENGTH
                    "tool_calls" -> FinishReason.TOOL_CALLS
                    "error"      -> FinishReason.ERROR
                    else         -> FinishReason.UNKNOWN
                }
                val interaction = buildInteraction(
                    operation    = pendingOperation!!,
                    status       = pendingStatus,
                    latencyMs    = pendingLatencyMs,
                    finishReason = finishReason,
                    timestamp    = pendingTimestamp
                )
                storage.insertInteraction(interaction)
                ApplicationManager.getApplication().messageBus
                    .syncPublisher(CopilotMonitorTopics.INTERACTION_EVENT)
                    .onInteraction(interaction)
                pendingOperation = null
            }
        }

        // If a request line was the last line with no finish-reason follow-up, emit without finish reason
        if (pendingOperation != null && pendingLatencyMs >= 0) {
            val interaction = buildInteraction(
                operation    = pendingOperation!!,
                status       = pendingStatus,
                latencyMs    = pendingLatencyMs,
                finishReason = FinishReason.UNKNOWN,
                timestamp    = pendingTimestamp
            )
            storage.insertInteraction(interaction)
            ApplicationManager.getApplication().messageBus
                .syncPublisher(CopilotMonitorTopics.INTERACTION_EVENT)
                .onInteraction(interaction)
        }
    }

    private fun buildInteraction(
        operation: String,
        status: Int,
        latencyMs: Long,
        finishReason: FinishReason,
        timestamp: Instant
    ): Interaction {
        val featureType = when (operation.lowercase()) {
            "fetchchat"        -> FeatureType.CHAT_ASK
            "fetchcompletion"  -> FeatureType.COMPLETION_INLINE
            "fetchagent",
            "bgagent"          -> FeatureType.CHAT_AGENT
            else               -> FeatureType.CHAT_ASK
        }

        // Estimate tokens: ~200 output tokens/s, input from context size
        val estimatedOutput = if (latencyMs > 0) (latencyMs / 5L).coerceAtMost(2000L) else 300L
        val estimatedInput = getContextTokenEstimate()

        return Interaction(
            timestamp        = timestamp,
            model            = "unknown",
            provider         = "github-copilot",
            featureType      = featureType,
            inputTokens      = estimatedInput,
            outputTokens     = estimatedOutput,
            latencyMs        = latencyMs,
            finishReason     = if (status >= 400) FinishReason.ERROR else finishReason,
            isEstimated      = true
        )
    }

    private fun getContextTokenEstimate(): Long {
        return try {
            // Use a default estimate since we don't have a project reference here.
            // ~3000 tokens is a reasonable mid-range for a chat turn.
            3000L
        } catch (_: Exception) {
            3000L
        }
    }

    private fun parseTimestamp(ts: String?): Instant {
        if (ts == null) return Instant.now()
        return try {
            val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            java.time.LocalDateTime.parse(ts, formatter)
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
        } catch (_: Exception) {
            Instant.now()
        }
    }
}
