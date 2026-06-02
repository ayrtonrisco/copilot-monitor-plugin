package com.github.copilotmonitor.services

import com.github.copilotmonitor.model.FeatureType
import com.github.copilotmonitor.model.FinishReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class SessionLogParserTest {

    private val parser = SessionLogParser()

    @Test
    fun `parseJsonSession extracts basic interaction`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("session.json")
        file.writeText("""
            {
              "sessionId": "abc123",
              "model": "gpt-4o",
              "provider": "openai",
              "featureType": "CHAT_ASK",
              "timestamp": "2026-06-02T10:30:00Z",
              "usage": {
                "prompt_tokens": 4200,
                "completion_tokens": 380,
                "cache_read_input_tokens": 3100,
                "cache_creation_input_tokens": 0
              },
              "latencyMs": 1240,
              "finishReason": "stop"
            }
        """.trimIndent())

        val interactions = parser.parseJsonSession(file)
        assertEquals(1, interactions.size)
        val i = interactions[0]
        assertEquals("gpt-4o", i.model)
        assertEquals("openai", i.provider)
        assertEquals(FeatureType.CHAT_ASK, i.featureType)
        assertEquals(4200L, i.inputTokens)
        assertEquals(380L, i.outputTokens)
        assertEquals(3100L, i.cacheReadTokens)
        assertEquals(1240L, i.latencyMs)
        assertEquals(FinishReason.STOP, i.finishReason)
        assertEquals("abc123", i.sessionId)
    }

    @Test
    fun `parseJsonSession handles missing optional fields`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("minimal.json")
        file.writeText("""{"model": "gpt-4o", "usage": {"prompt_tokens": 100, "completion_tokens": 50}}""")
        val interactions = parser.parseJsonSession(file)
        assertEquals(1, interactions.size)
        assertEquals("gpt-4o", interactions[0].model)
        assertEquals(100L, interactions[0].inputTokens)
    }

    @Test
    fun `parseJsonlSession extracts request events`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("session.jsonl")
        file.writeText("""
            {"event":"request","ts":"2026-06-02T10:31:00Z","model":"claude-sonnet-4-20250514","inputTokens":8400,"outputTokens":620,"cacheReadTokens":6200,"latencyMs":2100}
            {"event":"tool_call","ts":"2026-06-02T10:31:02Z","tool":"read_file","durationMs":45}
            {"event":"request","ts":"2026-06-02T10:31:05Z","model":"claude-sonnet-4-20250514","inputTokens":9100,"outputTokens":290}
        """.trimIndent())

        val interactions = parser.parseJsonlSession(file)
        assertEquals(2, interactions.size)
        assertEquals("claude-sonnet-4-20250514", interactions[0].model)
        assertEquals(8400L, interactions[0].inputTokens)
        assertEquals(6200L, interactions[0].cacheReadTokens)
        assertEquals(9100L, interactions[1].inputTokens)
    }

    @Test
    fun `parseJsonlSession skips malformed lines`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("bad.jsonl")
        file.writeText("""
            {"event":"request","ts":"2026-06-01T10:00:00Z","model":"gpt-4o","inputTokens":1000,"outputTokens":100}
            this is not json
            {"event":"request","ts":"2026-06-01T10:01:00Z","model":"gpt-4o","inputTokens":2000,"outputTokens":200}
        """.trimIndent())

        val interactions = parser.parseJsonlSession(file)
        assertEquals(2, interactions.size)
    }

    @Test
    fun `parseJsonSession returns empty list on malformed json`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("bad.json")
        file.writeText("this is not valid json")
        val interactions = parser.parseJsonSession(file)
        assertTrue(interactions.isEmpty())
    }

    @Test
    fun `provider is inferred when missing`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("session.json")
        file.writeText("""{"model":"claude-sonnet-4-20250514","usage":{"prompt_tokens":100,"completion_tokens":50}}""")
        val interactions = parser.parseJsonSession(file)
        assertEquals("anthropic", interactions[0].provider)
    }

    @Test
    fun `parseJsonlSession handles non-request events only`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("noevents.jsonl")
        file.writeText("""
            {"event":"tool_call","tool":"read_file"}
            {"event":"session_start","sessionId":"xyz"}
        """.trimIndent())
        val interactions = parser.parseJsonlSession(file)
        assertTrue(interactions.isEmpty())
    }
}
