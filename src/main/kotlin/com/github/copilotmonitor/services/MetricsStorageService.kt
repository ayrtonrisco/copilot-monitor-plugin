package com.github.copilotmonitor.services

import com.github.copilotmonitor.model.DailySummary
import com.github.copilotmonitor.model.FeatureType
import com.github.copilotmonitor.model.FinishReason
import com.github.copilotmonitor.model.Interaction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Service(Service.Level.APP)
open class MetricsStorageService {

    private val logger = thisLogger()
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val _connection: Connection by lazy { initDatabase() }

    open fun <T> withConnection(block: (Connection) -> T): T = block(_connection)

    private fun getDbPath(): Path {
        val systemDir = System.getProperty("idea.system.path")
            ?: (System.getProperty("user.home") + "/.copilot-monitor")
        val dir = Paths.get(systemDir, "copilot-monitor")
        Files.createDirectories(dir)
        return dir.resolve("metrics.db")
    }

    private fun initDatabase(): Connection {
        val dbPath = getDbPath()
        logger.info("Opening metrics database at $dbPath")
        Class.forName("org.sqlite.JDBC")
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL")
            stmt.execute("PRAGMA foreign_keys=ON")
        }
        runMigrationsWithConnection(conn)
        return conn
    }

    internal fun runMigrationsWithConnection(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version     INTEGER PRIMARY KEY,
                    applied_at  TEXT NOT NULL,
                    description TEXT NOT NULL
                )
            """.trimIndent())

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS interactions (
                    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp               TEXT NOT NULL,
                    session_id              TEXT NOT NULL DEFAULT '',
                    model                   TEXT NOT NULL,
                    provider                TEXT NOT NULL DEFAULT '',
                    feature_type            TEXT NOT NULL,
                    input_tokens            INTEGER NOT NULL DEFAULT 0,
                    output_tokens           INTEGER NOT NULL DEFAULT 0,
                    cache_read_tokens       INTEGER NOT NULL DEFAULT 0,
                    cache_creation_tokens   INTEGER NOT NULL DEFAULT 0,
                    reasoning_tokens        INTEGER NOT NULL DEFAULT 0,
                    latency_ms              INTEGER NOT NULL DEFAULT -1,
                    ttft_ms                 INTEGER NOT NULL DEFAULT -1,
                    finish_reason           TEXT NOT NULL DEFAULT 'UNKNOWN',
                    accepted                INTEGER,
                    is_estimated            INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_interactions_timestamp ON interactions(timestamp)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_interactions_model ON interactions(model)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_interactions_session ON interactions(session_id)")

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS daily_summary (
                    date                TEXT NOT NULL,
                    model               TEXT NOT NULL,
                    feature_type        TEXT NOT NULL,
                    total_input_tokens  INTEGER NOT NULL DEFAULT 0,
                    total_output_tokens INTEGER NOT NULL DEFAULT 0,
                    cache_read_tokens   INTEGER NOT NULL DEFAULT 0,
                    cache_hit_rate      REAL NOT NULL DEFAULT 0.0,
                    acceptance_rate     REAL,
                    avg_latency_ms      REAL NOT NULL DEFAULT 0.0,
                    avg_ttft_ms         REAL NOT NULL DEFAULT 0.0,
                    cost_estimate_usd   REAL NOT NULL DEFAULT 0.0,
                    interaction_count   INTEGER NOT NULL DEFAULT 0,
                    error_count         INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (date, model, feature_type)
                )
            """.trimIndent())

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS model_config (
                    model_id                TEXT PRIMARY KEY,
                    display_name            TEXT NOT NULL,
                    provider                TEXT NOT NULL DEFAULT '',
                    context_window          INTEGER NOT NULL DEFAULT 128000,
                    max_prompt              INTEGER NOT NULL DEFAULT 64000,
                    premium_multiplier      REAL NOT NULL DEFAULT 1.0,
                    cost_per_1k_input_usd   REAL NOT NULL DEFAULT 0.0,
                    cost_per_1k_output_usd  REAL NOT NULL DEFAULT 0.0,
                    cost_per_1k_cache_usd   REAL NOT NULL DEFAULT 0.0,
                    supports_cache          INTEGER NOT NULL DEFAULT 0,
                    last_updated            INTEGER NOT NULL
                )
            """.trimIndent())

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS alerts (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp     TEXT NOT NULL,
                    type          TEXT NOT NULL,
                    severity      TEXT NOT NULL,
                    message       TEXT NOT NULL,
                    acknowledged  INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())

            conn.prepareStatement(
                "INSERT OR IGNORE INTO schema_version (version, applied_at, description) VALUES (?, ?, ?)"
            ).use { ps ->
                ps.setInt(1, 1)
                ps.setString(2, Instant.now().toString())
                ps.setString(3, "initial schema")
                ps.executeUpdate()
            }
        }
    }

    fun insertInteraction(interaction: Interaction) {
        withConnection { conn ->
            try {
                conn.prepareStatement("""
                    INSERT INTO interactions (timestamp, session_id, model, provider, feature_type,
                        input_tokens, output_tokens, cache_read_tokens, cache_creation_tokens,
                        reasoning_tokens, latency_ms, ttft_ms, finish_reason, accepted, is_estimated)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { ps ->
                    ps.setString(1, interaction.timestamp.toString())
                    ps.setString(2, interaction.sessionId)
                    ps.setString(3, interaction.model)
                    ps.setString(4, interaction.provider)
                    ps.setString(5, interaction.featureType.name)
                    ps.setLong(6, interaction.inputTokens)
                    ps.setLong(7, interaction.outputTokens)
                    ps.setLong(8, interaction.cacheReadTokens)
                    ps.setLong(9, interaction.cacheCreationTokens)
                    ps.setLong(10, interaction.reasoningTokens)
                    ps.setLong(11, interaction.latencyMs)
                    ps.setLong(12, interaction.ttftMs)
                    ps.setString(13, interaction.finishReason.name)
                    ps.setObject(14, interaction.accepted?.let { if (it) 1 else 0 })
                    ps.setInt(15, if (interaction.isEstimated) 1 else 0)
                    ps.executeUpdate()
                }
            } catch (e: Exception) {
                logger.warn("Failed to insert interaction: ${e.message}")
            }
        }
    }

    fun getDailySummary(days: Int = 7): List<DailySummary> {
        val cutoff = LocalDate.now().minusDays(days.toLong()).format(dateFormatter)
        return withConnection { conn ->
            try {
                conn.prepareStatement("""
                    SELECT date, model, feature_type, total_input_tokens, total_output_tokens,
                        cache_read_tokens, cache_hit_rate, acceptance_rate, avg_latency_ms,
                        avg_ttft_ms, cost_estimate_usd, interaction_count, error_count
                    FROM daily_summary
                    WHERE date >= ?
                    ORDER BY date DESC
                """.trimIndent()).use { ps ->
                    ps.setString(1, cutoff)
                    ps.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.toDailySummary()) }
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to query daily summary: ${e.message}")
                emptyList()
            }
        }
    }

    fun getLastNInteractions(n: Int = 10): List<Interaction> {
        return withConnection { conn ->
            try {
                conn.prepareStatement("""
                    SELECT id, timestamp, session_id, model, provider, feature_type,
                        input_tokens, output_tokens, cache_read_tokens, cache_creation_tokens,
                        reasoning_tokens, latency_ms, ttft_ms, finish_reason, accepted, is_estimated
                    FROM interactions
                    ORDER BY timestamp DESC
                    LIMIT ?
                """.trimIndent()).use { ps ->
                    ps.setInt(1, n)
                    ps.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.toInteraction()) }
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to query interactions: ${e.message}")
                emptyList()
            }
        }
    }

    fun getTodayStats(): TodayStats {
        val today = LocalDate.now().format(dateFormatter)
        return withConnection { conn ->
            try {
                conn.prepareStatement("""
                    SELECT COALESCE(SUM(input_tokens), 0), COALESCE(SUM(output_tokens), 0),
                        COALESCE(SUM(cache_read_tokens), 0), COUNT(*)
                    FROM interactions
                    WHERE date(timestamp) = ?
                """.trimIndent()).use { ps ->
                    ps.setString(1, today)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) TodayStats(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4))
                        else TodayStats(0, 0, 0, 0)
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to query today stats: ${e.message}")
                TodayStats(0, 0, 0, 0)
            }
        }
    }

    fun getMonthlyTotal(): MonthlyTotal {
        val monthStart = LocalDate.now().withDayOfMonth(1).format(dateFormatter)
        return withConnection { conn ->
            try {
                conn.prepareStatement("""
                    SELECT COALESCE(SUM(input_tokens), 0), COALESCE(SUM(output_tokens), 0), COUNT(*)
                    FROM interactions
                    WHERE date(timestamp) >= ?
                """.trimIndent()).use { ps ->
                    ps.setString(1, monthStart)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) MonthlyTotal(rs.getLong(1), rs.getLong(2), rs.getInt(3))
                        else MonthlyTotal(0, 0, 0)
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to query monthly total: ${e.message}")
                MonthlyTotal(0, 0, 0)
            }
        }
    }

    fun getInteractionsForPeriod(days: Int): List<Interaction> {
        val cutoff = LocalDate.now().minusDays(days.toLong()).atStartOfDay(ZoneId.of("UTC")).toInstant()
        return withConnection { conn ->
            try {
                conn.prepareStatement("""
                    SELECT id, timestamp, session_id, model, provider, feature_type,
                        input_tokens, output_tokens, cache_read_tokens, cache_creation_tokens,
                        reasoning_tokens, latency_ms, ttft_ms, finish_reason, accepted, is_estimated
                    FROM interactions
                    WHERE timestamp >= ?
                    ORDER BY timestamp ASC
                """.trimIndent()).use { ps ->
                    ps.setString(1, cutoff.toString())
                    ps.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.toInteraction()) }
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to query interactions for period: ${e.message}")
                emptyList()
            }
        }
    }

    fun recordAlert(type: String, severity: String, message: String) {
        withConnection { conn ->
            try {
                conn.prepareStatement(
                    "INSERT INTO alerts (timestamp, type, severity, message) VALUES (?, ?, ?, ?)"
                ).use { ps ->
                    ps.setString(1, Instant.now().toString())
                    ps.setString(2, type)
                    ps.setString(3, severity)
                    ps.setString(4, message)
                    ps.executeUpdate()
                }
            } catch (e: Exception) {
                logger.warn("Failed to record alert: ${e.message}")
            }
        }
    }

    fun isAlertFiredThisMonth(type: String): Boolean {
        val monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneId.of("UTC")).toInstant()
        return withConnection { conn ->
            try {
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM alerts WHERE type = ? AND timestamp >= ?"
                ).use { ps ->
                    ps.setString(1, type)
                    ps.setString(2, monthStart.toString())
                    ps.executeQuery().use { rs -> rs.next() && rs.getInt(1) > 0 }
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    fun refreshDailySummary() {
        val today = LocalDate.now().format(dateFormatter)
        withConnection { conn ->
            try {
                conn.prepareStatement("""
                    INSERT OR REPLACE INTO daily_summary
                        (date, model, feature_type, total_input_tokens, total_output_tokens,
                         cache_read_tokens, cache_hit_rate, acceptance_rate, avg_latency_ms,
                         avg_ttft_ms, cost_estimate_usd, interaction_count, error_count)
                    SELECT
                        date(timestamp) as d,
                        model,
                        feature_type,
                        SUM(input_tokens),
                        SUM(output_tokens),
                        SUM(cache_read_tokens),
                        CASE WHEN SUM(input_tokens) > 0
                             THEN CAST(SUM(cache_read_tokens) AS REAL) / SUM(input_tokens)
                             ELSE 0.0 END,
                        CASE WHEN COUNT(CASE WHEN accepted IS NOT NULL THEN 1 END) > 0
                             THEN CAST(SUM(CASE WHEN accepted = 1 THEN 1 ELSE 0 END) AS REAL)
                                  / COUNT(CASE WHEN accepted IS NOT NULL THEN 1 END)
                             ELSE NULL END,
                        AVG(CASE WHEN latency_ms >= 0 THEN latency_ms ELSE NULL END),
                        AVG(CASE WHEN ttft_ms >= 0 THEN ttft_ms ELSE NULL END),
                        0.0,
                        COUNT(*),
                        SUM(CASE WHEN finish_reason = 'ERROR' THEN 1 ELSE 0 END)
                    FROM interactions
                    WHERE date(timestamp) = ?
                    GROUP BY date(timestamp), model, feature_type
                """.trimIndent()).use { ps ->
                    ps.setString(1, today)
                    ps.executeUpdate()
                }
            } catch (e: Exception) {
                logger.warn("Failed to refresh daily summary: ${e.message}")
            }
        }
    }

    private fun ResultSet.toDailySummary() = DailySummary(
        date = getString(1),
        model = getString(2),
        featureType = try { FeatureType.valueOf(getString(3)) } catch (_: Exception) { FeatureType.UNKNOWN },
        totalInputTokens = getLong(4),
        totalOutputTokens = getLong(5),
        totalCacheReadTokens = getLong(6),
        cacheHitRate = getDouble(7),
        acceptanceRate = getObject(8)?.let { (it as Number).toDouble() },
        avgLatencyMs = getDouble(9),
        avgTtftMs = getDouble(10),
        costEstimateUsd = getDouble(11),
        interactionCount = getInt(12),
        errorCount = getInt(13)
    )

    private fun ResultSet.toInteraction() = Interaction(
        id = getLong(1),
        timestamp = Instant.parse(getString(2)),
        sessionId = getString(3) ?: "",
        model = getString(4),
        provider = getString(5) ?: "",
        featureType = try { FeatureType.valueOf(getString(6)) } catch (_: Exception) { FeatureType.UNKNOWN },
        inputTokens = getLong(7),
        outputTokens = getLong(8),
        cacheReadTokens = getLong(9),
        cacheCreationTokens = getLong(10),
        reasoningTokens = getLong(11),
        latencyMs = getLong(12),
        ttftMs = getLong(13),
        finishReason = try { FinishReason.valueOf(getString(14)) } catch (_: Exception) { FinishReason.UNKNOWN },
        accepted = getObject(15)?.let { (it as Number).toInt() == 1 },
        isEstimated = getInt(16) == 1
    )

    data class TodayStats(
        val inputTokens: Long,
        val outputTokens: Long,
        val cacheReadTokens: Long,
        val interactionCount: Int
    )

    data class MonthlyTotal(
        val inputTokens: Long,
        val outputTokens: Long,
        val interactionCount: Int
    )
}
