# Data Sources — Copilot Monitor Plugin

## Source 1: Copilot Session Log Files (Primary)

GitHub Copilot for JetBrains writes session data to disk. This is the main data source.

### File Locations

```kotlin
fun getCopilotLogPaths(ideName: String): List<Path> {
    val home = System.getProperty("user.home")
    val xdgConfig = System.getenv("XDG_CONFIG_HOME") ?: "$home/.config"
    return when {
        SystemInfo.isMac -> listOf(
            Path.of("$home/Library/Application Support/JetBrains/$ideName/copilot"),
            Path.of("$home/Library/Application Support/JetBrains/$ideName/log")
        )
        SystemInfo.isLinux -> listOf(
            Path.of("$xdgConfig/JetBrains/$ideName/copilot"),
            Path.of("$xdgConfig/JetBrains/$ideName/log")
        )
        SystemInfo.isWindows -> listOf(
            Path.of(System.getenv("APPDATA"), "JetBrains", ideName, "copilot"),
            Path.of(System.getenv("APPDATA"), "JetBrains", ideName, "log")
        )
        else -> emptyList()
    }
}
```

### File Formats

#### Chat Sessions: `.json`
```json
{
  "sessionId": "abc123",
  "model": "gpt-4o",
  "provider": "openai",
  "featureType": "chat_ask",
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
```

#### CLI / Agent Sessions: `.jsonl` (one JSON object per line)
```jsonl
{"event":"request","ts":"2026-06-02T10:31:00Z","model":"claude-sonnet-4-20250514","inputTokens":8400,"outputTokens":620,"cacheReadTokens":6200,"latencyMs":2100}
{"event":"tool_call","ts":"2026-06-02T10:31:02Z","tool":"read_file","durationMs":45}
{"event":"request","ts":"2026-06-02T10:31:05Z","model":"claude-sonnet-4-20250514","inputTokens":9100,"outputTokens":290}
```

### Parsing Strategy

```kotlin
// SessionLogParser.kt
class SessionLogParser {
    
    fun parseJsonSession(file: Path): List<Interaction> {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val session = json.decodeFromString<CopilotSessionDto>(file.readText())
            listOf(session.toInteraction())
        } catch (e: Exception) {
            thisLogger().warn("Failed to parse session file ${file.name}: ${e.message}")
            emptyList()
        }
    }

    fun parseJsonlSession(file: Path): List<Interaction> {
        return file.readLines()
            .filter { it.startsWith("{") && it.contains("\"event\":\"request\"") }
            .mapNotNull { line ->
                try {
                    Json { ignoreUnknownKeys = true }
                        .decodeFromString<CopilotRequestEventDto>(line)
                        .toInteraction()
                } catch (e: Exception) {
                    null  // skip malformed lines silently
                }
            }
    }
}
```

### File Watching

```kotlin
// Register in SessionLogService.init()
VirtualFileManager.getInstance().addVirtualFileListener(object : VirtualFileListener {
    override fun contentsChanged(event: VirtualFileChangeEvent) {
        if (event.file.extension in listOf("json", "jsonl")) {
            scope.launch(Dispatchers.IO) {
                parseAndStore(event.file.toNioPath())
            }
        }
    }
})
```

---

## Source 2: IntelliJ Platform APIs (Context Window)

Use IDE APIs to estimate current context composition:

```kotlin
// ContextWindowService.kt — call from background thread, then post to EDT
fun getCurrentContextEstimate(project: Project): ContextWindowStatus {
    val editorManager = FileEditorManager.getInstance(project)
    val openFiles = editorManager.openFiles
    val activeEditor = editorManager.selectedTextEditor

    val totalChars = openFiles.sumOf { vf -> 
        runCatching { vf.contentsToByteArray().size }.getOrDefault(0) 
    }
    val selectionChars = activeEditor?.selectionModel?.selectedText?.length ?: 0
    val estimatedTokens = (totalChars / 3.5).toLong()

    val modelConfig = ModelConfigRepository.get(currentModel())
    val utilizationPct = if (modelConfig.maxPrompt > 0) 
        estimatedTokens.toDouble() / modelConfig.maxPrompt * 100 else 0.0

    return ContextWindowStatus(
        usedTokensEstimate = estimatedTokens,
        maxPromptTokens = modelConfig.maxPrompt,
        utilizationPct = utilizationPct,
        openTabCount = openFiles.size,
        activeFileSizeChars = totalChars.toLong(),
        selectionSizeChars = selectionChars.toLong(),
        currentModel = currentModel(),
        warning = when {
            utilizationPct > 80 -> ContextWindowWarning.CRITICAL
            utilizationPct > 50 -> ContextWindowWarning.APPROACHING_LIMIT
            else -> null
        }
    )
}
```

---

## Source 3: GitHub Copilot Metrics API (Optional, Org-level)

Only used when `settings.githubApiEnabled = true` and token is configured.

```
GET https://api.github.com/orgs/{org}/copilot/metrics
Authorization: Bearer {PAT}
X-GitHub-Api-Version: 2022-11-28
```

Rate limiting: poll at most once per hour. Cache responses in `org_metrics` table.

Required OAuth scopes: `read:org`, `manage_billing:copilot`

---

## Source 4: OpenTelemetry Ingest (Optional, VS Code bridge)

If the user also uses VS Code Copilot with OTel enabled, the plugin can act as an OTLP
receiver on `localhost:4317` (gRPC) or `localhost:4318` (HTTP) to receive spans from
VS Code and correlate with IntelliJ usage.

This is an advanced feature (Phase 3). Do not implement in Phase 1.

---

## Token Count Accuracy

| Source              | Accuracy | Label in UI |
|---------------------|----------|-------------|
| Session log files   | ~85–95%  | `~12,400`   |
| OTel span attributes| Exact    | `12,400`    |
| IDE API estimation  | ~60–80%  | `~est.`     |
| GitHub API          | Exact    | `12,400`    |

Always show estimated values with a `~` prefix or `(est.)` label.
