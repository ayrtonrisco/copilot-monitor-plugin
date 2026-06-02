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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
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
    private val logPath: Path by lazy { Paths.get(PathManager.getLogPath(), "idea.log") }
    private val diagPath: Path by lazy {
        Paths.get(PathManager.getSystemPath(), "copilot-monitor", "diagnostics.log")
    }

    // Most precise: actual aggregate token data for a full turn (real counts, not estimated)
    // Example: [roundMetricsTracker] Turn token usage: 74520 prompt, 2183 completion, 49632 cached (cache rate: 66.6%)
    private val metricsRegex = Regex(
        """#copilot.*\[roundMetricsTracker\].*Turn token usage: (\d+) prompt, (\d+) completion, (\d+) cached \(cache rate: ([\d.]+)%\)"""
    )

    // Model name from AutoModelService: [AutoModelService] Fetched auto model for active in 774ms: claude-haiku-4.5
    private val autoModelRegex = Regex(
        """#copilot.*\[AutoModelService\].*Fetched auto model for active.*?:\s*(\S+)"""
    )

    // Fallback: individual API call (gives latency; tokens estimated)
    // Example: [fetchChat] Request ... at <https://...> finished with 200 status after 1488.97ms
    private val requestRegex = Regex(
        """#copilot.*\[(\w+)\].*finished with (\d+) status after ([\d.]+)ms"""
    )

    // Finish reason companion line
    private val finishRegex = Regex("""#copilot.*finish reason: \[(\w+)\]""")

    // Timestamp at start of log line
    private val tsRegex = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})""")

    @Volatile
    private var currentModel = "unknown"

    fun start() {
        diag("=== IdeaLogWatcher started ===")
        diag("Log path : $logPath  exists=${Files.exists(logPath)}")
        diag("Diag path: $diagPath")
        val size = try { Files.size(logPath).also { logPosition.set(it) } } catch (e: Exception) {
            diag("Could not read log size: ${e.message}")
            logPosition.set(0L)
            -1L
        }
        diag("Seeking to end of file: offset=$size")
        scheduler.scheduleWithFixedDelay({ poll() }, 5, 10, TimeUnit.SECONDS)
    }

    fun stop() {
        scheduler.shutdown()
    }

    private fun poll() {
        try {
            if (!Files.exists(logPath)) {
                diag("idea.log not found — waiting")
                return
            }
            val fileSize = Files.size(logPath)
            if (fileSize < logPosition.get()) {
                diag("Log rotated — resetting position to 0")
                logPosition.set(0L)
            }
            if (fileSize == logPosition.get()) return

            val newLines = readNewLines(logPath, logPosition.get())
            logPosition.set(fileSize)

            val copilotLines = newLines.filter { "#copilot" in it }
            if (copilotLines.isNotEmpty()) {
                diag("Poll: ${newLines.size} new lines, ${copilotLines.size} #copilot lines")
                copilotLines.forEach { diag("  >> $it") }
            }

            processLines(newLines)
        } catch (e: Exception) {
            diag("Poll error: ${e.message}")
            logger.warn("[CopilotMonitor] IdeaLogWatcher poll error: ${e.message}")
        }
    }

    private fun readNewLines(path: Path, fromOffset: Long): List<String> {
        val lines = mutableListOf<String>()
        RandomAccessFile(path.toFile(), "r").use { raf ->
            raf.seek(fromOffset)
            // Use UTF-8 BufferedReader on top of the RandomAccessFile channel
            val buf = ByteArray(65536)
            val sb = StringBuilder()
            var bytesRead: Int
            while (raf.read(buf).also { bytesRead = it } != -1) {
                sb.append(String(buf, 0, bytesRead, StandardCharsets.UTF_8))
            }
            sb.lines().forEach { lines.add(it) }
        }
        return lines
    }

    internal fun processLines(lines: List<String>) {
        // Track pending fetchChat data for fallback interactions
        var pendingLatencyMs: Long = -1
        var pendingStatus: Int = 200
        var pendingOperation: String? = null
        var pendingTimestamp: Instant = Instant.now()
        // Whether the current batch produced a roundMetricsTracker entry for recent fetchChat
        var metricsSeenSinceFetchChat = false

        for (line in lines) {

            // ── PRIMARY: roundMetricsTracker with REAL token counts ──────────────
            val metricsMatch = metricsRegex.find(line)
            if (metricsMatch != null) {
                val promptTokens      = metricsMatch.groupValues[1].toLongOrNull() ?: 0L
                val completionTokens  = metricsMatch.groupValues[2].toLongOrNull() ?: 0L
                val cachedTokens      = metricsMatch.groupValues[3].toLongOrNull() ?: 0L
                val ts = tsRegex.find(line)?.groupValues?.get(1)
                val interaction = Interaction(
                    timestamp          = parseTimestamp(ts),
                    model              = currentModel,
                    provider           = inferProvider(currentModel),
                    featureType        = FeatureType.CHAT_ASK,
                    inputTokens        = promptTokens,
                    outputTokens       = completionTokens,
                    cacheReadTokens    = cachedTokens,
                    latencyMs          = pendingLatencyMs,
                    finishReason       = FinishReason.STOP,
                    isEstimated        = false
                )
                storage.insertInteraction(interaction)
                publish(interaction)
                diag("REAL interaction stored — prompt=$promptTokens out=$completionTokens cached=$cachedTokens model=$currentModel")
                metricsSeenSinceFetchChat = true
                pendingLatencyMs = -1
                pendingOperation = null
                continue
            }

            // ── Model name from AutoModelService ─────────────────────────────────
            val modelMatch = autoModelRegex.find(line)
            if (modelMatch != null) {
                currentModel = modelMatch.groupValues[1].trim()
                diag("Model updated to: $currentModel")
                continue
            }

            // ── fetchChat: track latency, use as fallback if no roundMetricsTracker ──
            val reqMatch = requestRegex.find(line)
            if (reqMatch != null) {
                pendingOperation  = reqMatch.groupValues[1]
                pendingStatus     = reqMatch.groupValues[2].toIntOrNull() ?: 200
                pendingLatencyMs  = reqMatch.groupValues[3].toDoubleOrNull()?.toLong() ?: -1L
                pendingTimestamp  = parseTimestamp(tsRegex.find(line)?.groupValues?.get(1))
                metricsSeenSinceFetchChat = false
                continue
            }

            // ── Finish-reason: emit fallback interaction if no roundMetricsTracker ──
            val finMatch = finishRegex.find(line)
            if (finMatch != null && pendingOperation != null && !metricsSeenSinceFetchChat) {
                val frStr = finMatch.groupValues[1].lowercase()
                val fr = when (frStr) {
                    "stop"       -> FinishReason.STOP
                    "length"     -> FinishReason.LENGTH
                    "tool_calls" -> FinishReason.TOOL_CALLS
                    "error"      -> FinishReason.ERROR
                    else         -> FinishReason.UNKNOWN
                }
                emitFallback(pendingOperation!!, pendingStatus, pendingLatencyMs, fr, pendingTimestamp)
                pendingOperation = null
            }
        }

        // End of batch: emit fallback if fetchChat was seen without roundMetricsTracker
        if (pendingOperation != null && !metricsSeenSinceFetchChat && pendingLatencyMs >= 0) {
            emitFallback(pendingOperation!!, pendingStatus, pendingLatencyMs, FinishReason.UNKNOWN, pendingTimestamp)
        }
    }

    private fun emitFallback(op: String, status: Int, latencyMs: Long, fr: FinishReason, ts: Instant) {
        val estimatedOut   = if (latencyMs > 0) (latencyMs / 5L).coerceAtMost(2000L) else 300L
        val featureType = when (op.lowercase()) {
            "fetchchat"       -> FeatureType.CHAT_ASK
            "fetchcompletion" -> FeatureType.COMPLETION_INLINE
            "fetchagent", "bgagent" -> FeatureType.CHAT_AGENT
            else              -> FeatureType.CHAT_ASK
        }
        val interaction = Interaction(
            timestamp    = ts,
            model        = currentModel,
            provider     = inferProvider(currentModel),
            featureType  = featureType,
            inputTokens  = 3000L,
            outputTokens = estimatedOut,
            latencyMs    = latencyMs,
            finishReason = if (status >= 400) FinishReason.ERROR else fr,
            isEstimated  = true
        )
        storage.insertInteraction(interaction)
        publish(interaction)
        diag("ESTIMATED interaction stored — op=$op latency=${latencyMs}ms model=$currentModel")
    }

    private fun publish(interaction: Interaction) {
        ApplicationManager.getApplication().messageBus
            .syncPublisher(CopilotMonitorTopics.INTERACTION_EVENT)
            .onInteraction(interaction)
    }

    private fun inferProvider(model: String): String = when {
        "claude" in model  -> "anthropic"
        "gpt" in model || "o1" in model || "o3" in model -> "openai"
        "gemini" in model  -> "google"
        else               -> "github-copilot"
    }

    private fun parseTimestamp(ts: String?): Instant {
        if (ts == null) return Instant.now()
        return try {
            java.time.LocalDateTime.parse(ts,
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
        } catch (_: Exception) { Instant.now() }
    }

    private fun diag(message: String) {
        logger.info("[CopilotMonitor] $message")
        try {
            Files.createDirectories(diagPath.parent)
            Files.write(
                diagPath,
                "[${Instant.now()}] $message\n".toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND
            )
        } catch (_: Exception) {}
    }
}
