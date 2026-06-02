package com.github.copilotmonitor.services

import com.github.copilotmonitor.CopilotMonitorTopics
import com.github.copilotmonitor.model.Interaction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.APP)
class SessionLogService : AutoCloseable {

    private val logger = thisLogger()
    private val parser = SessionLogParser()
    private val storage: MetricsStorageService by lazy { service() }
    private val scope = CoroutineScope(Dispatchers.IO)

    private val processedFiles = ConcurrentHashMap<String, Long>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "copilot-monitor-log-poll").also { it.isDaemon = true }
    }

    private var ideName: String = "IdeaIC2024.1"
    private val ideaLogWatcher = IdeaLogWatcher()
    private val initialized = AtomicBoolean(false)

    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        ideName = detectIdeName()
        registerFileWatcher()
        startPolling()
        processExistingFiles()
        ideaLogWatcher.start()
        logger.info("[CopilotMonitor] SessionLogService initialized, watching idea.log")
    }

    private fun detectIdeName(): String {
        return try {
            val buildProps = System.getProperty("idea.paths.selector") ?: ""
            if (buildProps.isNotEmpty()) buildProps else "IdeaIC2024.1"
        } catch (_: Exception) {
            "IdeaIC2024.1"
        }
    }

    private fun getCopilotLogPaths(): List<Path> {
        val home = System.getProperty("user.home")
        val xdgConfig = System.getenv("XDG_CONFIG_HOME") ?: "$home/.config"
        return when {
            SystemInfo.isMac -> listOf(
                Paths.get(home, "Library", "Application Support", "JetBrains", ideName, "copilot"),
                Paths.get(home, "Library", "Application Support", "JetBrains", ideName, "log")
            )
            SystemInfo.isLinux -> listOf(
                Paths.get(xdgConfig, "JetBrains", ideName, "copilot"),
                Paths.get(xdgConfig, "JetBrains", ideName, "log")
            )
            SystemInfo.isWindows -> listOf(
                Paths.get(System.getenv("APPDATA") ?: home, "JetBrains", ideName, "copilot"),
                Paths.get(System.getenv("APPDATA") ?: home, "JetBrains", ideName, "log"),
                Paths.get(System.getenv("LOCALAPPDATA") ?: home, "JetBrains", ideName, "copilot"),
                Paths.get(System.getenv("LOCALAPPDATA") ?: home, "JetBrains", ideName, "log"),
                Paths.get(System.getenv("LOCALAPPDATA") ?: home, "github-copilot")
            )
            else -> emptyList()
        }
    }

    private fun registerFileWatcher() {
        ApplicationManager.getApplication().messageBus.connect().subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    events.forEach { event ->
                        val path = event.path
                        if (path.endsWith(".json") || path.endsWith(".jsonl")) {
                            val logPaths = getCopilotLogPaths()
                            if (logPaths.any { path.startsWith(it.toString()) }) {
                                scope.launch { parseAndStore(Paths.get(path)) }
                            }
                        }
                    }
                }
            }
        )
    }

    private fun startPolling() {
        scheduler.scheduleWithFixedDelay({
            try {
                processExistingFiles()
            } catch (e: Exception) {
                logger.warn("Error during log polling: ${e.message}")
            }
        }, 10, 10, TimeUnit.SECONDS)
    }

    private fun processExistingFiles() {
        getCopilotLogPaths().forEach { dir ->
            if (Files.isDirectory(dir)) {
                try {
                    Files.walk(dir, 3).use { stream ->
                        stream.filter { path ->
                            val name = path.fileName?.toString() ?: ""
                            (name.endsWith(".json") || name.endsWith(".jsonl")) && Files.isRegularFile(path)
                        }.forEach { file ->
                            val lastModified = Files.getLastModifiedTime(file).toMillis()
                            val alreadyProcessed = processedFiles[file.toString()]
                            if (alreadyProcessed == null || alreadyProcessed < lastModified) {
                                scope.launch { parseAndStore(file) }
                                processedFiles[file.toString()] = lastModified
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.debug("Could not walk directory $dir: ${e.message}")
                }
            }
        }
    }

    private fun parseAndStore(file: Path) {
        try {
            val ext = file.fileName?.toString()?.substringAfterLast('.') ?: return
            val interactions = when (ext) {
                "json" -> parser.parseJsonSession(file)
                "jsonl" -> parser.parseJsonlSession(file)
                else -> return
            }
            interactions.forEach { interaction ->
                storage.insertInteraction(interaction)
                publishInteraction(interaction)
            }
            if (interactions.isNotEmpty()) {
                logger.debug("Parsed ${interactions.size} interactions from ${file.fileName}")
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse/store file ${file.fileName}: ${e.message}")
        }
    }

    private fun publishInteraction(interaction: Interaction) {
        ApplicationManager.getApplication().messageBus
            .syncPublisher(CopilotMonitorTopics.INTERACTION_EVENT)
            .onInteraction(interaction)
    }

    override fun close() {
        scheduler.shutdown()
        ideaLogWatcher.stop()
    }
}
