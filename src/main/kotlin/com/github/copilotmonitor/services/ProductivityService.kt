package com.github.copilotmonitor.services

import com.github.copilotmonitor.model.CodeGenerationStats
import com.github.copilotmonitor.model.DailyFluency
import com.github.copilotmonitor.model.FeatureType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Service(Service.Level.APP)
class ProductivityService {

    private val storage: MetricsStorageService by lazy { service() }
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun getAcceptanceRate(days: Int, featureType: FeatureType? = null): Double? {
        val interactions = storage.getInteractionsForPeriod(days)
            .filter { it.accepted != null }
            .let { if (featureType != null) it.filter { i -> i.featureType == featureType } else it }

        if (interactions.isEmpty()) return null
        val accepted = interactions.count { it.accepted == true }
        return accepted.toDouble() / interactions.size
    }

    fun getCodeGenerationStats(days: Int): CodeGenerationStats {
        val interactions = storage.getInteractionsForPeriod(days)
        val total = interactions.size
        val suggestedLines = interactions.sumOf { (it.outputTokens * 0.6).toLong() }
        val acceptedInteractions = interactions.filter { it.accepted == true }
        val acceptedLines = acceptedInteractions.sumOf { (it.outputTokens * 0.6).toLong() }
        val byFeature = interactions.groupBy { it.featureType }.mapValues { (_, v) -> v.size }

        return CodeGenerationStats(
            suggestedLinesEstimate = suggestedLines,
            acceptedLinesEstimate = acceptedLines,
            acceptanceByLanguage = emptyMap(),
            totalInteractions = total,
            byFeatureType = byFeature
        )
    }

    fun computeFluencyScore(days: Int): Int {
        val interactions = storage.getInteractionsForPeriod(days)
        if (interactions.isEmpty()) return 0

        val interactionsPerDay = interactions.size.toDouble() / days
        val uniqueFeatureTypes = interactions.map { it.featureType }.distinct().size
        val acceptanceRate = interactions.filter { it.accepted != null }.let { completions ->
            if (completions.isEmpty()) 0.5
            else completions.count { it.accepted == true }.toDouble() / completions.size
        }
        val totalInput = interactions.sumOf { it.inputTokens }
        val totalOutput = interactions.sumOf { it.outputTokens }
        val tokenEfficiency = if (totalInput > 0)
            (totalOutput.toDouble() / totalInput).coerceAtMost(0.5) / 0.5
        else 0.0

        val usageFrequency = (interactionsPerDay / 20.0).coerceAtMost(1.0) * 30
        val featureDiversity = (uniqueFeatureTypes / 4.0).coerceAtMost(1.0) * 20
        val acceptanceQuality = acceptanceRate * 30
        val tokenEfficiencyScore = tokenEfficiency * 20

        return (usageFrequency + featureDiversity + acceptanceQuality + tokenEfficiencyScore).toInt()
    }

    fun getFluencyTrend(days: Int): List<DailyFluency> {
        return (0 until days).map { daysAgo ->
            val date = LocalDate.now().minusDays(daysAgo.toLong()).format(dateFormatter)
            val dayInteractions = storage.getInteractionsForPeriod(1)
                .filter { it.timestamp.toString().startsWith(date) }

            val acceptanceRate = dayInteractions.filter { it.accepted != null }.let { completions ->
                if (completions.isEmpty()) null
                else completions.count { it.accepted == true }.toDouble() / completions.size
            }

            DailyFluency(
                date = date,
                score = computeFluencyScore(1),
                interactionsPerDay = dayInteractions.size.toDouble(),
                acceptanceRate = acceptanceRate
            )
        }.reversed()
    }
}
