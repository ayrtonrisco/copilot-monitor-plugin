# Architecture — Copilot Monitor Plugin

## Design Principles

1. **Local-first**: all storage and processing on device; zero telemetry without opt-in
2. **Non-intrusive**: never intercept or modify Copilot traffic; read-only on Copilot log files
3. **EDT-safe**: all I/O on background threads; UI updates via `ApplicationManager.getApplication().invokeLater`
4. **Fail-silent**: if Copilot log format changes, degrade gracefully and surface a warning; never crash the IDE
5. **Zero code capture**: never store source code, prompt text, or LLM response content

---

## Component Diagram

```
┌─────────────────────────────────────────────────────────┐
│                   IntelliJ IDE Process                    │
│                                                           │
│  ┌─────────────────┐     ┌──────────────────────────┐   │
│  │  Status Bar      │     │   Tool Window (JCEF)     │   │
│  │  Widget          │     │   ┌──────────────────┐   │   │
│  │  TokenUsage      │     │   │  Dashboard JS/   │   │   │
│  │  StatusBarWidget │     │   │  Chart.js panels │   │   │
│  └────────┬─────────┘     └──────────┬───────────┘   │   │
│           │                          │                   │
│           └──────────┬───────────────┘                   │
│                      ▼                                    │
│         ┌────────────────────────┐                       │
│         │   MetricsFacade        │  (single entry point  │
│         │   (ApplicationService) │   for all UI queries) │
│         └────────────┬───────────┘                       │
│                      │                                    │
│      ┌───────────────┼───────────────────┐               │
│      ▼               ▼                   ▼               │
│  ┌───────────┐ ┌──────────────┐ ┌──────────────────┐    │
│  │ Session   │ │  Metrics     │ │  ContextWindow   │    │
│  │ LogService│ │  Storage     │ │  Service         │    │
│  │           │ │  Service     │ │  (IDE APIs)      │    │
│  │ Watches   │ │  (SQLite)    │ │                  │    │
│  │ .json/    │ └──────┬───────┘ └──────────────────┘    │
│  │ .jsonl    │        │                                   │
│  └───────────┘        │                                   │
│                       │                                   │
│      ┌────────────────┼────────────────┐                 │
│      ▼                ▼                ▼                  │
│  ┌────────┐  ┌─────────────┐  ┌──────────────┐          │
│  │ Cache  │  │  Cost       │  │  OTel Export │          │
│  │ Anal.  │  │  Estimation │  │  Service     │          │
│  │ Service│  │  Service    │  │  (opt-in)    │          │
│  └────────┘  └─────────────┘  └──────────────┘          │
│                                                           │
└─────────────────────────────────────────────────────────┘
```

---

## Service Responsibilities

### `SessionLogService` (ApplicationService)
- Discovers Copilot session log files on all platforms:
  - **macOS:** `~/Library/Application Support/JetBrains/<IDE>/copilot/`
  - **Linux:** `~/.config/JetBrains/<IDE>/copilot/` or `$XDG_CONFIG_HOME/...`
  - **Windows:** `%APPDATA%\JetBrains\<IDE>\copilot\`
- Registers a `VirtualFileListener` for file change events
- Falls back to polling every 10s if VFS watcher unavailable
- Parses `.json` (Chat sessions) and `.jsonl` (CLI/Agent sessions)
- Extracts: `model`, `input_tokens`, `output_tokens`, `cache_read_tokens`, `cache_creation_tokens`, `reasoning_tokens`, `latency_ms`, `finish_reason`, `feature_type`, `timestamp`
- Emits `InteractionEvent` via application message bus

### `MetricsStorageService` (ApplicationService)
- Manages SQLite connection pool (1 writer, N readers)
- Inserts `Interaction` records on `InteractionEvent`
- Provides query API: `getDailySummary()`, `getSessionStats()`, `getModelBreakdown()`
- Runs DB migrations on plugin startup (version table + Flyway-style scripts)

### `ContextWindowService` (ProjectService)
- Reads `FileEditorManager` to count open tabs and active file size
- Estimates token count: `charCount / 3.5` (approximate, label as estimate)
- Polls `ModelConfigRepository` for `max_prompt` of current model
- Exposes `ContextWindowStatus(usedTokens, maxTokens, utilizationPct, openFileCount)`

### `CacheAnalysisService` (ApplicationService)
- Aggregates `cache_read_tokens` and `cache_creation_tokens` from DB
- Computes `cacheHitRate = cache_read / input_tokens * 100`
- Generates recommendations when hit rate drops below configurable threshold

### `CostEstimationService` (ApplicationService)
- Loads model pricing from `ModelConfigRepository`
- Computes cost: `(input_tokens / 1000) * cost_per_1k_input + ...`
- Tracks against monthly budget from `CopilotMonitorSettings`
- Fires `BudgetAlertEvent` at 75%, 90%, 100% thresholds

### `OtelExportService` (ApplicationService, opt-in)
- Activated only when `settings.otelEnabled = true`
- Builds `OpenTelemetrySDK` with `OtlpHttpSpanExporter` / `OtlpGrpcSpanExporter`
- Maps `Interaction` to OTel spans following GenAI Semantic Conventions
- Attributes: `gen_ai.request.model`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`,
  `gen_ai.usage.cache_read.input_tokens`, `gen_ai.usage.cache_creation.input_tokens`,
  `gen_ai.response.finish_reasons`, `gen_ai.provider.name`

### `AlertNotificationService` (ApplicationService)
- Subscribes to `BudgetAlertEvent`, `ContextWindowWarningEvent`, `CacheHitRateAlertEvent`
- Uses IntelliJ `NotificationGroupManager` to show balloon notifications
- Respects `settings.alertCooldownMinutes` to prevent notification fatigue

---

## Event Bus (IntelliJ Application Message Bus)

Define a `Topic` for each event type:

```kotlin
// In CopilotMonitorTopics.kt
object CopilotMonitorTopics {
    val INTERACTION_EVENT: Topic<InteractionListener> = Topic.create(
        "CopilotMonitor.InteractionEvent", InteractionListener::class.java
    )
    val BUDGET_ALERT: Topic<BudgetAlertListener> = Topic.create(
        "CopilotMonitor.BudgetAlert", BudgetAlertListener::class.java
    )
    val CONTEXT_WINDOW_WARNING: Topic<ContextWindowWarningListener> = Topic.create(
        "CopilotMonitor.ContextWindowWarning", ContextWindowWarningListener::class.java
    )
}
```

---

## Dependency Injection

Use IntelliJ's service locator pattern. Do NOT use Spring or Koin:

```kotlin
// Retrieve a service
val metricsStorage = service<MetricsStorageService>()              // ApplicationService
val contextWindow = project.service<ContextWindowService>()        // ProjectService
```

Register in `plugin.xml`:
```xml
<extensions defaultExtensionNs="com.intellij">
  <applicationService serviceImplementation="...MetricsStorageService"/>
  <applicationService serviceImplementation="...SessionLogService"/>
  <projectService serviceImplementation="...ContextWindowService"/>
</extensions>
```

---

## Threading Model

```
EDT (Swing thread)        → UI reads only; no I/O
CoroutineScope(IO)        → file parsing, DB writes, HTTP calls
CoroutineScope(Default)   → CPU-bound computations (cache stats, cost calc)
invokeLater { }           → post results back to EDT for UI update
```

Use `PluginCoroutineScope` (available since platform 233) for structured concurrency tied to plugin lifecycle.
