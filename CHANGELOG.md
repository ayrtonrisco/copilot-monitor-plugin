# Changelog

All notable changes to Copilot Monitor are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [1.0.0] — 2026-06-02

### Added

- **Context Window Monitor** — real-time utilization bar with per-tab breakdown and APPROACHING / CRITICAL / EXCEEDED warnings
- **Token & Cost Tracking** — daily, session, and monthly totals for input, output, and cache tokens; per-model cost breakdown; monthly projection based on 7-day rolling average
- **Budget Alerts** — configurable monthly budget with balloon notifications at 75%, 90%, and 100% thresholds
- **LLM Cache Analysis** — cache hit rate gauge (today / 7d / 30d), donut chart, trend line, savings estimate, and auto-generated recommendations
- **Performance Metrics** — TTFT histogram, latency by model comparison, error rate trend, finish-reason distribution, rate-limit and token-limit exceeded detection
- **Productivity Score** — acceptance rate, AI fluency score (0–100), interactions/hour, feature-usage breakdown
- **Model Comparison** — side-by-side table of context window vs. Copilot effective limit, cost/1K, premium multiplier, cache support, and p50 TTFT
- **OpenTelemetry Export** (opt-in) — OTLP HTTP/gRPC exporter following GenAI Semantic Conventions; send spans to Grafana, Jaeger, Datadog, or any OTLP-compatible backend
- **Status Bar Widget** — color-coded token count (green/yellow/red) with hover tooltip showing today's usage and cost
- **Dashboard Tool Window** — 7-panel JCEF webview (Overview, Context, Tokens, Cache, Performance, Models, Export) powered by Chart.js 4.x
- **Settings Page** — budgets, thresholds, alert cooldowns, data retention, OTel endpoint, CSV/JSON export, DB path
- **Privacy-first** — all data stored locally in SQLite (`<IDE_SYSTEM_DIR>/copilot-monitor/metrics.db`); no code, prompts, or file names stored; zero network calls without explicit opt-in
- Parses GitHub Copilot `.json` (Chat) and `.jsonl` (Agent/CLI) session log files on macOS, Linux, and Windows
- Live file watching via IntelliJ `VirtualFileListener`; polling fallback every 10 s if VFS watcher unavailable
- SQLite schema migrations with version tracking; seed data for GPT-4o, GPT-4.1, Claude Sonnet/Opus 4, Gemini 2.5 Pro, o3

[1.0.0]: https://github.com/ayrtonrisco/copilot-monitor-plugin/releases/tag/v1.0.0
