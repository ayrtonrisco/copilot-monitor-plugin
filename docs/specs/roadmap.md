# Roadmap — Copilot Monitor Plugin

## Phase 1: MVP (Weeks 1–8)
**Goal:** Working plugin that reads Copilot logs and shows basic token usage.

### Infrastructure
- [ ] **INFRA-1:** Gradle project setup with `gradle-intellij-plugin 2.x`, Kotlin DSL, JVM 21
- [ ] **INFRA-2:** `plugin.xml` with all extension point declarations
- [ ] **INFRA-3:** SQLite integration with `sqlite-jdbc`; `MetricsStorageService` with migration support
- [ ] **INFRA-4:** Domain models: `Interaction`, `SessionSummary`, `ModelConfig` data classes
- [ ] **INFRA-5:** `CopilotMonitorTopics` message bus topics
- [ ] **INFRA-6:** Test infrastructure: JUnit 5 + MockK + in-memory SQLite fixtures

### Core Services
- [ ] **SVC-1:** `SessionLogService` — file discovery on macOS/Linux/Windows
- [ ] **SVC-2:** `SessionLogService` — `.json` Chat session parser
- [ ] **SVC-3:** `SessionLogService` — `.jsonl` CLI/Agent session parser
- [ ] **SVC-4:** `SessionLogService` — VirtualFileListener for live updates
- [ ] **SVC-5:** `ModelConfigRepository` — seed data + SQLite read/write
- [ ] **SVC-6:** `MetricsStorageService` — insert, query daily summary, query last N interactions

### UI
- [ ] **UI-1:** `TokenUsageStatusBarWidget` — today's tokens, color states, tooltip
- [ ] **UI-2:** `CopilotMonitorToolWindowFactory` + JCEF browser setup
- [ ] **UI-3:** Kotlin ↔ JS bridge (JBCefJSQuery)
- [ ] **UI-4:** Overview panel webview (KPI tiles + basic token trend chart)
- [ ] **UI-5:** Tokens panel webview (daily chart + model breakdown)
- [ ] **UI-6:** Basic `CopilotMonitorConfigurable` settings page

### Context Window (basic)
- [ ] **CW-1 to CW-3:** ContextWindowService with tab counting and token estimation
- [ ] **CW-6:** Context panel webview (utilization bar, open tab count)

---

## Phase 2: Analysis Features (Weeks 9–14)

### Cache Analysis
- [ ] **CACHE-1 to CACHE-6** (all tasks from `llm-cache.md`)

### Performance
- [ ] **PERF-1 to PERF-6** (all tasks from `performance.md`)

### Cost & Budget
- [ ] **TC-1 to TC-5** (all tasks from `token-cost.md`)

### Context Window (advanced)
- [ ] **CW-4 to CW-7** (remaining tasks from `context-window.md`)

### Notifications
- [ ] **NOTIF-1:** `AlertNotificationService` with all alert types
- [ ] **NOTIF-2:** Budget alerts at 75/90/100% thresholds
- [ ] **NOTIF-3:** Context window critical warnings
- [ ] **NOTIF-4:** Cache hit rate low alerts
- [ ] **NOTIF-5:** Rate limit and token limit exceeded alerts

---

## Phase 3: Advanced Integrations (Weeks 15–20)

### Productivity & Models
- [ ] **PROD-1 to PROD-3** (all tasks from `productivity.md`)
- [ ] **MODEL-1 to MODEL-4** (all tasks from `productivity.md`)

### OpenTelemetry Export (opt-in)
- [ ] **OTEL-1:** `OtelExportService` with OTLP HTTP exporter
- [ ] **OTEL-2:** Map `Interaction` → OTel spans with GenAI semantic convention attributes
- [ ] **OTEL-3:** OTLP gRPC exporter support
- [ ] **OTEL-4:** Export panel in settings with endpoint config and test button
- [ ] **OTEL-5:** OTel metrics (`gen_ai.client.token.usage`, `gen_ai.client.operation.duration`)

### GitHub API Integration (opt-in)
- [ ] **GH-1:** OAuth flow via GitHub for PAT-based authentication
- [ ] **GH-2:** Fetch org-level metrics from `/orgs/{org}/copilot/metrics` API
- [ ] **GH-3:** Store in `org_metrics` table; display team comparison in Overview

---

## Phase 4: Optimization & Publication (Ongoing)

- [ ] **PUB-1:** Plugin marketplace listing preparation (icon, description, screenshots)
- [ ] **PUB-2:** Compatibility testing on IntelliJ 2024.1 through 2026.1
- [ ] **PUB-3:** JetBrains Marketplace submission and review
- [ ] **OPT-1:** Model recommendation ML (local, based on historical interaction data)
- [ ] **OPT-2:** CI/CD integration (publish metrics as PR comments)
- [ ] **OPT-3:** Team sync backend (Docker + SQLite, GitHub auth)

---

## Implementation Order for Claude Code CLI

When using this project with Claude Code CLI, tackle tasks in this order:

```
1. INFRA-1 → INFRA-6         (project scaffolding)
2. SVC-1 → SVC-6             (data pipeline)
3. UI-1 → UI-6               (basic UI shell)
4. CW-1 → CW-3               (MVP context monitoring)
→ Verify: ./gradlew runIde — plugin loads, status bar shows, tool window opens

5. CACHE-1 → CACHE-6         (cache analysis)
6. PERF-1 → PERF-6           (performance metrics)
7. TC-1 → TC-5               (cost tracking)
8. CW-4 → CW-7               (advanced context features)
9. NOTIF-1 → NOTIF-5         (smart notifications)
→ Verify: ./gradlew test — all unit tests pass

10. PROD-1 → MODEL-4         (productivity & model analysis)
11. OTEL-1 → OTEL-5         (telemetry export)
→ Verify: ./gradlew buildPlugin && ./gradlew verifyPlugin
```

## Definition of Done

Each task is done when:
1. Implementation compiles with no warnings (`./gradlew build`)
2. Unit tests pass (`./gradlew test`)
3. Feature works in sandbox IDE (`./gradlew runIde`)
4. Privacy invariant maintained: no code/prompt content stored or logged
