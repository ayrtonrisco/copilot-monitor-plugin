# Copilot Monitor — IntelliJ Plugin

## Project Overview

IntelliJ/JetBrains plugin that monitors GitHub Copilot AI usage in real time:
context window utilization, LLM cache efficiency, token consumption, latency,
and productivity metrics. Privacy-first: all data local by default, zero code capture.

**Target IDEs:** IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider, CLion (IntelliJ Platform SDK)
**Language:** Kotlin (JVM 21+)
**Min platform version:** 2024.1 (build 241)

---

## Architecture & Specs

@docs/specs/architecture.md
@docs/specs/data-model.md
@docs/specs/ui-components.md
@docs/modules/context-window.md
@docs/modules/llm-cache.md
@docs/modules/token-cost.md
@docs/modules/performance.md
@docs/modules/productivity.md
@docs/modules/models.md
@docs/specs/data-sources.md
@docs/specs/roadmap.md

---

## Tech Stack

| Layer            | Technology                                    |
|------------------|-----------------------------------------------|
| Plugin core      | Kotlin + IntelliJ Platform SDK                |
| UI panels        | JCEF (Chromium Embedded) + webview            |
| Charts           | Chart.js 4.x (bundled in webview resources)   |
| Local storage    | SQLite via `sqlite-jdbc` 3.x                  |
| Log parsing      | kotlinx.serialization (JSON/JSONL)            |
| Telemetry export | OpenTelemetry SDK for Java (OTLP HTTP/gRPC)   |
| Build system     | Gradle (Kotlin DSL) + `gradle-intellij-plugin` 2.x |
| Tests            | JUnit 5 + MockK + IntelliJ test fixtures      |

---

## Repository Structure

```
copilot-monitor-plugin/
├── CLAUDE.md                          ← this file
├── docs/
│   ├── specs/
│   │   ├── architecture.md
│   │   ├── data-model.md
│   │   ├── ui-components.md
│   │   ├── data-sources.md
│   │   └── roadmap.md
│   └── modules/
│       ├── context-window.md
│       ├── llm-cache.md
│       ├── token-cost.md
│       ├── performance.md
│       ├── productivity.md
│       └── models.md
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── plugin.xml                         ← src/main/resources/META-INF/plugin.xml
├── src/
│   ├── main/
│   │   ├── kotlin/com/github/copilotmonitor/
│   │   │   ├── CopilotMonitorPlugin.kt
│   │   │   ├── services/
│   │   │   │   ├── SessionLogService.kt
│   │   │   │   ├── MetricsStorageService.kt
│   │   │   │   ├── ContextWindowService.kt
│   │   │   │   ├── CacheAnalysisService.kt
│   │   │   │   ├── CostEstimationService.kt
│   │   │   │   └── OtelExportService.kt
│   │   │   ├── toolwindow/
│   │   │   │   ├── CopilotMonitorToolWindowFactory.kt
│   │   │   │   └── panels/
│   │   │   ├── statusbar/
│   │   │   │   └── TokenUsageStatusBarWidget.kt
│   │   │   ├── settings/
│   │   │   │   └── CopilotMonitorSettings.kt
│   │   │   ├── model/
│   │   │   │   ├── Interaction.kt
│   │   │   │   ├── SessionSummary.kt
│   │   │   │   └── ModelConfig.kt
│   │   │   └── notifications/
│   │   │       └── AlertNotificationService.kt
│   │   └── resources/
│   │       ├── META-INF/plugin.xml
│   │       └── webview/
│   │           ├── index.html
│   │           ├── dashboard.js
│   │           └── charts.js
│   └── test/
│       └── kotlin/com/github/copilotmonitor/
└── src/main/resources/META-INF/plugin.xml
```

---

## Build & Run Commands

```bash
# Build
./gradlew build

# Run plugin in sandbox IDE
./gradlew runIde

# Run tests
./gradlew test

# Run tests with coverage
./gradlew test jacocoTestReport

# Package plugin for distribution
./gradlew buildPlugin

# Verify plugin compatibility
./gradlew verifyPlugin

# Check for dependency updates
./gradlew dependencyUpdates
```

---

## Code Conventions

- **Package root:** `com.github.copilotmonitor`
- **Kotlin style:** official Kotlin coding conventions; no wildcard imports
- **Services:** register as `ApplicationService` (project-level if needed); use constructor injection
- **Coroutines:** use `CoroutineScope(Dispatchers.IO)` for I/O; UI updates via `invokeLater`
- **Error handling:** never throw from service init; log with `thisLogger()` and degrade gracefully
- **Logging:** `thisLogger().info/warn/error()`; no `println`; no sensitive data in logs
- **Privacy rule:** NEVER log or store prompt content, code content, or file names unless explicitly opted-in by the user
- **Null safety:** prefer Kotlin null safety over Optional; avoid `!!` except in tests
- **Naming:** services are `*Service`, models are plain data classes, factories are `*Factory`

---

## Key Invariants

- All data persists to SQLite in `<IDE_SYSTEM_DIR>/copilot-monitor/metrics.db`
- No network calls unless user explicitly enables cloud sync or OTLP export
- Plugin must not block the EDT (Event Dispatch Thread)
- File watchers use `VirtualFileManager`, not raw `java.io.File` polling
- Log file parsing is read-only; plugin never writes to Copilot log files
- Token counts are estimates when read from local logs; label as "~" in UI

---

## Testing Requirements

- Unit tests for all service logic (SessionLogService, CacheAnalysisService, CostEstimationService)
- Integration test for SQLite read/write using in-memory database
- Use `MockK` for mocking IntelliJ services
- Minimum coverage target: 70% on `services/` package
- Test data fixtures in `src/test/resources/fixtures/` (sample .json and .jsonl session files)
