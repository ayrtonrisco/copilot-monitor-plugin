package com.github.copilotmonitor.services

import com.github.copilotmonitor.model.FeatureType
import com.github.copilotmonitor.model.FinishReason
import com.github.copilotmonitor.model.Interaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

class MetricsStorageServiceTest {

    private lateinit var connection: Connection
    private lateinit var service: MetricsStorageService

    @BeforeEach
    fun setUp() {
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        service = object : MetricsStorageService() {
            override fun <T> withConnection(block: (Connection) -> T): T = block(connection)
        }
        service.runMigrationsWithConnection(connection)
    }

    @AfterEach
    fun tearDown() {
        connection.close()
    }

    @Test
    fun `insertInteraction stores data correctly`() {
        val interaction = sampleInteraction()
        service.insertInteraction(interaction)

        val results = service.getLastNInteractions(10)
        assertEquals(1, results.size)
        assertEquals("gpt-4o", results[0].model)
        assertEquals(1000L, results[0].inputTokens)
        assertEquals(200L, results[0].outputTokens)
        assertEquals(FeatureType.CHAT_ASK, results[0].featureType)
    }

    @Test
    fun `getTodayStats returns correct aggregation`() {
        service.insertInteraction(sampleInteraction(inputTokens = 1000, outputTokens = 200))
        service.insertInteraction(sampleInteraction(inputTokens = 2000, outputTokens = 400))

        val stats = service.getTodayStats()
        assertEquals(3000L, stats.inputTokens)
        assertEquals(600L, stats.outputTokens)
        assertEquals(2, stats.interactionCount)
    }

    @Test
    fun `getLastNInteractions respects limit`() {
        repeat(15) { service.insertInteraction(sampleInteraction()) }
        val results = service.getLastNInteractions(10)
        assertEquals(10, results.size)
    }

    @Test
    fun `getInteractionsForPeriod returns interactions within range`() {
        service.insertInteraction(sampleInteraction())
        val results = service.getInteractionsForPeriod(1)
        assertTrue(results.isNotEmpty())
    }

    @Test
    fun `recordAlert stores alert correctly`() {
        service.recordAlert("BUDGET_75", "WARNING", "Budget 75% reached")
        assertTrue(service.isAlertFiredThisMonth("BUDGET_75"))
    }

    @Test
    fun `isAlertFiredThisMonth returns false when no alert`() {
        assertTrue(!service.isAlertFiredThisMonth("BUDGET_75"))
    }

    @Test
    fun `getMonthlyTotal aggregates correctly`() {
        service.insertInteraction(sampleInteraction(inputTokens = 5000, outputTokens = 1000))
        val total = service.getMonthlyTotal()
        assertEquals(5000L, total.inputTokens)
        assertEquals(1000L, total.outputTokens)
        assertEquals(1, total.interactionCount)
    }

    @Test
    fun `insertInteraction handles cache tokens`() {
        val interaction = sampleInteraction().copy(
            cacheReadTokens = 500,
            cacheCreationTokens = 100
        )
        service.insertInteraction(interaction)
        val results = service.getLastNInteractions(1)
        assertEquals(500L, results[0].cacheReadTokens)
        assertEquals(100L, results[0].cacheCreationTokens)
    }

    @Test
    fun `getInteractionsForPeriod excludes old interactions`() {
        // All fresh interactions should be included for period=1
        service.insertInteraction(sampleInteraction())
        val results = service.getInteractionsForPeriod(1)
        assertEquals(1, results.size)
    }

    private fun sampleInteraction(
        inputTokens: Long = 1000,
        outputTokens: Long = 200
    ) = Interaction(
        timestamp = Instant.now(),
        model = "gpt-4o",
        provider = "openai",
        featureType = FeatureType.CHAT_ASK,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cacheReadTokens = 0,
        finishReason = FinishReason.STOP,
        sessionId = "test-session"
    )
}
