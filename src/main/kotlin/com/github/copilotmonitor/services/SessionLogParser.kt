package com.github.copilotmonitor.services

import com.github.copilotmonitor.model.FeatureType
import com.github.copilotmonitor.model.FinishReason
import com.github.copilotmonitor.model.Interaction
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Path
import java.time.Instant

class SessionLogParser {

    private val logger = thisLogger()
    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class CopilotSessionDto(
        val sessionId: String = "",
        val model: String = "unknown",
        val provider: String = "",
        val featureType: String = "UNKNOWN",
        val timestamp: String = "",
        val usage: UsageDto? = null,
        val latencyMs: Long = -1,
        val finishReason: String = "UNKNOWN"
    )

    @Serializable
    private data class UsageDto(
        @SerialName("prompt_tokens") val promptTokens: Long = 0,
        @SerialName("completion_tokens") val completionTokens: Long = 0,
        @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Long = 0,
        @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Long = 0,
        @SerialName("reasoning_tokens") val reasoningTokens: Long = 0
    )

    @Serializable
    private data class CopilotRequestEventDto(
        val event: String = "",
        val ts: String = "",
        val model: String = "unknown",
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val cacheReadTokens: Long = 0,
        val cacheCreationTokens: Long = 0,
        val reasoningTokens: Long = 0,
        val latencyMs: Long = -1,
        val finishReason: String = "UNKNOWN",
        val sessionId: String = ""
    )

    fun parseJsonSession(file: Path): List<Interaction> {
        return try {
            val text = file.toFile().readText()
            val dto = lenientJson.decodeFromString<CopilotSessionDto>(text)
            listOf(dto.toInteraction())
        } catch (e: Exception) {
            logger.warn("Failed to parse JSON session file ${file.fileName}: ${e.message}")
            emptyList()
        }
    }

    fun parseJsonlSession(file: Path): List<Interaction> {
        return try {
            file.toFile().readLines()
                .filter { it.startsWith("{") }
                .mapNotNull { line ->
                    try {
                        val obj = lenientJson.decodeFromString<JsonObject>(line)
                        val event = obj["event"]?.jsonPrimitive?.contentOrNull
                        if (event == "request") {
                            lenientJson.decodeFromString<CopilotRequestEventDto>(line).toInteraction()
                        } else null
                    } catch (_: Exception) {
                        null
                    }
                }
        } catch (e: Exception) {
            logger.warn("Failed to parse JSONL session file ${file.fileName}: ${e.message}")
            emptyList()
        }
    }

    private fun CopilotSessionDto.toInteraction() = Interaction(
        timestamp = parseTimestamp(timestamp),
        model = model,
        provider = provider.ifEmpty { inferProvider(model) },
        featureType = parseFeatureType(featureType),
        inputTokens = usage?.promptTokens ?: 0,
        outputTokens = usage?.completionTokens ?: 0,
        cacheReadTokens = usage?.cacheReadInputTokens ?: 0,
        cacheCreationTokens = usage?.cacheCreationInputTokens ?: 0,
        reasoningTokens = usage?.reasoningTokens ?: 0,
        latencyMs = latencyMs,
        finishReason = parseFinishReason(finishReason),
        sessionId = sessionId
    )

    private fun CopilotRequestEventDto.toInteraction() = Interaction(
        timestamp = parseTimestamp(ts),
        model = model,
        provider = inferProvider(model),
        featureType = FeatureType.CLI_SESSION,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cacheReadTokens = cacheReadTokens,
        cacheCreationTokens = cacheCreationTokens,
        reasoningTokens = reasoningTokens,
        latencyMs = latencyMs,
        finishReason = parseFinishReason(finishReason),
        sessionId = sessionId
    )

    private fun parseTimestamp(ts: String): Instant = try {
        if (ts.isEmpty()) Instant.now() else Instant.parse(ts)
    } catch (_: Exception) {
        Instant.now()
    }

    private fun parseFeatureType(raw: String): FeatureType = try {
        FeatureType.valueOf(raw.uppercase().replace("-", "_"))
    } catch (_: Exception) {
        when (raw.lowercase()) {
            "chat_ask", "ask" -> FeatureType.CHAT_ASK
            "chat_edit", "edit" -> FeatureType.CHAT_EDIT
            "chat_agent", "agent" -> FeatureType.CHAT_AGENT
            "inline", "completion", "completion_inline" -> FeatureType.COMPLETION_INLINE
            "code_review" -> FeatureType.CODE_REVIEW
            else -> FeatureType.UNKNOWN
        }
    }

    private fun parseFinishReason(raw: String): FinishReason = try {
        FinishReason.valueOf(raw.uppercase())
    } catch (_: Exception) {
        when (raw.lowercase()) {
            "stop" -> FinishReason.STOP
            "length", "max_tokens" -> FinishReason.LENGTH
            "tool_calls" -> FinishReason.TOOL_CALLS
            "error" -> FinishReason.ERROR
            else -> FinishReason.UNKNOWN
        }
    }

    private fun inferProvider(modelId: String): String = when {
        modelId.contains("claude") -> "anthropic"
        modelId.contains("gemini") -> "google"
        modelId.startsWith("gpt") || modelId.startsWith("o") -> "openai"
        else -> "unknown"
    }
}
