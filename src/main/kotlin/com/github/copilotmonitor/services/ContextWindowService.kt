package com.github.copilotmonitor.services

import com.github.copilotmonitor.CopilotMonitorTopics
import com.github.copilotmonitor.ContextWindowWarningEvent
import com.github.copilotmonitor.model.ContextWindowStatus
import com.github.copilotmonitor.model.ContextWindowWarning
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class ContextWindowService(private val project: Project) : AutoCloseable {

    private val logger = thisLogger()
    private val modelRepo: ModelConfigRepository by lazy { service() }
    private val scope = CoroutineScope(Dispatchers.IO)
    private var pollingJob: Job? = null

    private val currentStatus = AtomicReference<ContextWindowStatus>(null)
    private var currentModel: String = "gpt-4o"
    private var lastWarningFired: Map<ContextWindowWarning, Instant> = emptyMap()

    fun startPolling() {
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    val status = computeCurrentStatus()
                    currentStatus.set(status)
                    checkAndFireWarnings(status)
                } catch (e: Exception) {
                    logger.debug("Context window polling error: ${e.message}")
                }
                delay(5_000)
            }
        }
    }

    fun getCurrentStatus(): ContextWindowStatus? = currentStatus.get()

    fun setCurrentModel(modelId: String) {
        currentModel = modelId
    }

    private fun computeCurrentStatus(): ContextWindowStatus {
        var totalChars = 0L
        var selectionChars = 0L
        var tabCount = 0

        ApplicationManager.getApplication().runReadAction {
            try {
                val editorManager = FileEditorManager.getInstance(project)
                val openFiles = editorManager.openFiles
                tabCount = openFiles.size
                totalChars = openFiles.sumOf { vf ->
                    runCatching { vf.length }.getOrDefault(0L)
                }
                selectionChars = editorManager.selectedTextEditor
                    ?.selectionModel?.selectedText?.length?.toLong() ?: 0L
            } catch (_: Exception) {}
        }

        val systemPromptEstimate = 2_500L
        val estimatedTokens = (totalChars / 3.5).toLong() + systemPromptEstimate
        val maxPrompt = modelRepo.getMaxPrompt(currentModel)
        val utilizationPct = if (maxPrompt > 0) estimatedTokens.toDouble() / maxPrompt * 100.0 else 0.0

        val warning = when {
            utilizationPct > 80 -> ContextWindowWarning.CRITICAL
            utilizationPct > 50 -> ContextWindowWarning.APPROACHING_LIMIT
            else -> null
        }

        return ContextWindowStatus(
            usedTokensEstimate = estimatedTokens,
            maxPromptTokens = maxPrompt,
            utilizationPct = utilizationPct,
            openTabCount = tabCount,
            activeFileSizeChars = totalChars,
            selectionSizeChars = selectionChars,
            currentModel = currentModel,
            warning = warning
        )
    }

    private fun checkAndFireWarnings(status: ContextWindowStatus) {
        val warning = status.warning ?: return
        val now = Instant.now()
        val lastFired = lastWarningFired[warning]
        if (lastFired != null && now.epochSecond - lastFired.epochSecond < 300) return

        lastWarningFired = lastWarningFired + (warning to now)
        val recs = generateRecommendations(status)

        ApplicationManager.getApplication().messageBus
            .syncPublisher(CopilotMonitorTopics.CONTEXT_WINDOW_WARNING)
            .onContextWindowWarning(
                ContextWindowWarningEvent(warning, status.utilizationPct, currentModel, recs)
            )
    }

    fun generateRecommendations(status: ContextWindowStatus): List<String> {
        val recs = mutableListOf<String>()
        if (status.openTabCount > 5) {
            recs.add("Close unused tabs to free ~${status.openTabCount * 500} tokens")
        }
        if (status.utilizationPct > 80) {
            recs.add("Consider switching to a model with a larger max_prompt")
            recs.add("Use /compact or start a new chat session to reset context")
        }
        return recs
    }

    override fun close() {
        pollingJob?.cancel()
    }
}
