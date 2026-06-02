# Copilot Monitor

[![JetBrains Plugin](https://img.shields.io/badge/JetBrains%20Marketplace-Copilot%20Monitor-blue?logo=jetbrains&style=flat-square)](https://plugins.jetbrains.com/plugin/32072-copilot-monitor)
[![Version](https://img.shields.io/badge/version-1.1.0-brightgreen?style=flat-square)](https://github.com/ayrtonrisco/copilot-monitor-plugin/releases)
[![Platform](https://img.shields.io/badge/platform-2024.1%2B-orange?style=flat-square)](https://plugins.jetbrains.com/plugin/32072-copilot-monitor)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg?style=flat-square)](LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/ayrtonrisco/copilot-monitor-plugin/build.yml?branch=main&style=flat-square)](https://github.com/ayrtonrisco/copilot-monitor-plugin/actions)

**Real-time GitHub Copilot AI usage monitoring for IntelliJ-based IDEs.**

Track context window utilization, LLM cache efficiency, token consumption, latency, and productivity metrics — all locally, with zero code capture.

---

## Features

### Context Window Monitor
- Live utilization bar showing how much of the Copilot effective `max_prompt` is in use
- Per-component breakdown: open files, selection, chat history, system prompt
- `APPROACHING` / `CRITICAL` / `EXCEEDED` warnings with actionable recommendations
- Highlights the gap between `context_window` and the Copilot-enforced `max_prompt`

### Token & Cost Tracking
- Today / session / monthly totals for input, output, and cache tokens
- Per-model and per-feature cost breakdown (chat, completions, agent, code review)
- Monthly projection based on a rolling 7-day average
- Configurable monthly budget with balloon alerts at 75 %, 90 %, and 100 %

### LLM Cache Analysis
- Cache hit rate gauge (today / 7d / 30d) for Claude and other cache-capable models
- Donut chart: `cache_read` vs. `cache_creation` vs. fresh input vs. output tokens
- 30-day trend line and estimated savings vs. no-cache scenario
- Auto-generated recommendations to improve cache effectiveness

### Performance Metrics
- TTFT histogram and latency-by-model comparison (p50 / p90 / p99)
- Error rate trend, `finish_reason` distribution (STOP / LENGTH / TOOL_CALLS / ERROR)
- Rate-limit and token-limit-exceeded detection with timeline overlay

### Productivity Score
- Acceptance rate for inline completions (overall and by feature type)
- AI Fluency Score (0–100): usage frequency + feature diversity + acceptance quality + token efficiency
- Lines suggested / lines accepted estimates

### Model Comparison
- Side-by-side table: context window, Copilot effective limit, cost/1K, premium multiplier, cache support, p50 TTFT
- "Context window gap" visualization showing inaccessible tokens per model
- Model-selection recommendations based on task context and token budget

### OpenTelemetry Export *(opt-in)*
- OTLP HTTP or gRPC exporter following [GenAI Semantic Conventions](https://opentelemetry.io/docs/specs/semconv/gen-ai/)
- Send spans to Grafana, Jaeger, Datadog, or any OTLP-compatible backend
- Attributes: `gen_ai.request.model`, `gen_ai.usage.input_tokens`, `gen_ai.usage.cache_read.input_tokens`, etc.

### Privacy-First
- All data stored locally in SQLite (`<IDE_SYSTEM_DIR>/copilot-monitor/metrics.db`)
- Zero network calls without explicit opt-in
- No source code, prompts, file names, or LLM responses ever stored or logged

---

## Supported IDEs

| IDE | Min version |
|-----|-------------|
| IntelliJ IDEA | 2024.1 |
| PyCharm | 2024.1 |
| WebStorm | 2024.1 |
| GoLand | 2024.1 |
| Rider | 2024.1 |
| CLion | 2024.1 |

---

## Installation

### From JetBrains Marketplace *(recommended)*

1. Open **Settings / Preferences → Plugins → Marketplace**
2. Search for **Copilot Monitor**
3. Click **Install** and restart the IDE

Or install directly:

[![Install from Marketplace](https://img.shields.io/badge/Install-JetBrains%20Marketplace-blue?logo=jetbrains&style=for-the-badge)](https://plugins.jetbrains.com/plugin/32072-copilot-monitor)

### From disk

Download the latest `.zip` from [Releases](https://github.com/ayrtonrisco/copilot-monitor-plugin/releases), then:

**Settings → Plugins → ⚙ → Install Plugin from Disk…**

---

## Quick Start

After installation:

1. The **status bar widget** appears at the bottom of the IDE (`🤖 ~0 tokens`).
2. Open the **Copilot Monitor** tool window (right sidebar or **View → Tool Windows → Copilot Monitor**).
3. Use Copilot as usual — metrics appear automatically as session log files are parsed.
4. Configure budgets and thresholds under **Settings → Tools → Copilot Monitor**.

---

## Dashboard Panels

| Panel | What it shows |
|-------|---------------|
| **Overview** | KPI tiles (today / session / acceptance / cost), 7-day token trend, feature breakdown, recent interactions |
| **Context** | Utilization bar, context composition breakdown, open-tab recommendations |
| **Tokens** | Daily stacked chart, budget gauge, monthly projection, model/feature breakdown |
| **Cache** | Hit rate gauges, donut chart, 30-day trend, savings estimate, recommendations |
| **Performance** | TTFT histogram, latency by model, error rate, finish-reason distribution |
| **Models** | Usage distribution, comparison table with context window gap, model recommendations |
| **Export** | OTel endpoint configuration and test button |

---

## Status Bar Widget

| State | Display |
|-------|---------|
| Normal | `🤖 ~12.4K` (green) |
| > 70 % budget | `🤖 ~12.4K ⚠` (yellow) |
| > 90 % budget | `🤖 ~12.4K 🔴` (red) |
| Session limit close | `🤖 ~12.4K ⏱` (orange) |
| No data yet | `🤖 —` |

Click the widget to open the dashboard. Hover for a tooltip with today's token count, cost, and context window utilization.

---

## Data Sources

Copilot Monitor reads **Copilot session log files written by the JetBrains Copilot extension** — it never intercepts or modifies Copilot traffic.

| Platform | Log path |
|----------|----------|
| macOS | `~/Library/Application Support/JetBrains/<IDE>/copilot/` |
| Linux | `~/.config/JetBrains/<IDE>/copilot/` |
| Windows | `%APPDATA%\JetBrains\<IDE>\copilot\` |

Supported formats: `.json` (Chat sessions) and `.jsonl` (Agent / CLI sessions).
File changes are detected via IntelliJ `VirtualFileListener`; a 10-second polling fallback is used if the VFS watcher is unavailable.

---

## Supported Models

Pre-seeded pricing and context window data for:

| Model | Provider | Context Window | Copilot Limit | Cache |
|-------|----------|---------------|---------------|-------|
| GPT-4o | OpenAI | 128K | 64K | — |
| GPT-4.1 | OpenAI | 1M | 128K | — |
| GPT-5.2 Codex | OpenAI | 400K | 400K | — |
| o3 | OpenAI | 200K | 128K | — |
| Claude Sonnet 4 | Anthropic | 200K | 128K | ✓ |
| Claude Opus 4 | Anthropic | 200K | 128K | ✓ |
| Gemini 2.5 Pro | Google | 1M | 128K | — |

Model configs are stored in SQLite and can be updated without reinstalling the plugin.

---

## Settings

**Settings / Preferences → Tools → Copilot Monitor**

- **General:** status bar visibility, monthly token/cost budget, alert thresholds and cooldowns
- **Context Window:** warning and critical utilization thresholds
- **Cache:** low hit-rate alert threshold (default 30 %)
- **Export:** OTel endpoint (HTTP/gRPC), include content toggle
- **Data:** retention period, export to CSV/JSON, clear all data, database path

---

## Building from Source

**Requirements:** JDK 21+, Gradle (wrapper included)

```bash
# Build
./gradlew build

# Run plugin in a sandbox IDE
./gradlew runIde

# Run tests
./gradlew test

# Run tests with coverage report
./gradlew test jacocoTestReport

# Package for distribution
./gradlew buildPlugin

# Verify plugin compatibility
./gradlew verifyPlugin
```

Coverage target: ≥ 70 % instruction coverage on the `services/` package.

---

## Architecture Overview

```
IntelliJ IDE Process
├── Status Bar Widget        ← color-coded token count
├── Tool Window (JCEF)       ← Chart.js dashboard (7 panels)
│
├── MetricsFacade            ← single entry point for all UI queries
│
├── SessionLogService        ← watches .json/.jsonl Copilot log files
├── MetricsStorageService    ← SQLite (sqlite-jdbc 3.x)
├── ContextWindowService     ← FileEditorManager + token estimation
├── CacheAnalysisService     ← hit rate, savings, recommendations
├── CostEstimationService    ← per-model pricing, budget alerts
├── OtelExportService        ← OTLP HTTP/gRPC (opt-in)
└── AlertNotificationService ← IntelliJ balloon notifications
```

All I/O runs on background coroutines (`Dispatchers.IO`). The EDT is used only for UI reads and updates via `invokeLater`.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Plugin core | Kotlin + IntelliJ Platform SDK |
| UI panels | JCEF (Chromium Embedded) + webview |
| Charts | Chart.js 4.x |
| Local storage | SQLite via `sqlite-jdbc` 3.x |
| Log parsing | `kotlinx.serialization` (JSON/JSONL) |
| Telemetry export | OpenTelemetry SDK for Java (OTLP HTTP/gRPC) |
| Build system | Gradle (Kotlin DSL) + `gradle-intellij-plugin` 2.x |
| Tests | JUnit 5 + MockK + IntelliJ test fixtures |

---

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Commit your changes following [Conventional Commits](https://www.conventionalcommits.org/)
4. Run `./gradlew build` to verify everything compiles and tests pass
5. Open a Pull Request

Please read the architecture docs in [`docs/specs/`](docs/specs/) before contributing new services.

---

## License

[GPL-3.0](LICENSE) — © 2026 Ayrton Rafael Risco Torres
