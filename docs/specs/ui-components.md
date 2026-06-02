# UI Components — Copilot Monitor Plugin

## 1. Status Bar Widget

**Class:** `TokenUsageStatusBarWidget` implements `StatusBarWidget`

**Display format:**
```
🤖 ~12.4K tokens  (today)
```
- Green icon: utilization < 50%
- Yellow icon: utilization 50–80%  
- Red icon: utilization > 80% or budget alert active

**Click behavior:** opens the Tool Window and shows the Overview panel.

**Tooltip on hover:**
```
Copilot Monitor
Today: ~12,400 tokens  ($0.031)
Session: ~3,200 tokens
Context window: 48% used
Click to open dashboard
```

**Registration in `plugin.xml`:**
```xml
<statusBarWidgetFactory 
    implementation="com.github.copilotmonitor.statusbar.TokenUsageStatusBarWidgetFactory"
    id="CopilotMonitor.TokenUsage"
    order="after copilotStatusBarWidget"/>
```

---

## 2. Tool Window

**Class:** `CopilotMonitorToolWindowFactory` implements `ToolWindowFactory`

**Registration:**
```xml
<toolWindow id="Copilot Monitor"
            anchor="right"
            icon="/icons/copilotMonitor.svg"
            factoryClass="...CopilotMonitorToolWindowFactory"
            secondary="false"/>
```

### Panel Structure

```
┌─────────────────────────────────────────────┐
│ Copilot Monitor          [⚙] [↻] [?]        │
├─────────────────────────────────────────────┤
│ [Overview] [Context] [Tokens] [Cache]        │
│ [Perf]     [Models]  [Export]               │
├─────────────────────────────────────────────┤
│                                             │
│        (panel content via JCEF)             │
│                                             │
└─────────────────────────────────────────────┘
```

Each tab is a JCEF browser panel loading `webview/index.html?panel=<name>`.
The Kotlin side communicates with the webview via `JBCefJSQuery` and `browser.executeJavaScript()`.

### Kotlin → JS bridge (pushing data to webview)

```kotlin
// In each panel's controller
fun updatePanel(data: String) {
    browser.executeJavaScript("window.copilotMonitor.update($data)", "", 0)
}
```

### JS → Kotlin bridge (user actions from webview)

```kotlin
val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
query.addHandler { request ->
    when (Json.parseToJsonElement(request).jsonObject["action"]?.jsonPrimitive?.content) {
        "openSettings" -> openSettings()
        "exportData"   -> exportData()
        else           -> null
    }
    JBCefJSQuery.Response("ok")
}
```

---

## 3. Tab Panels (Webview)

### 3.1 Overview Panel (`panel=overview`)

Layout:
```
┌────────────┬────────────┬────────────┬────────────┐
│ Today      │ Session    │ Acceptance │ Cost Est.  │
│ ~12.4K tok │ ~3.2K tok  │ Rate: 72%  │ $0.031     │
│ input+out  │            │            │            │
└────────────┴────────────┴────────────┴────────────┘
┌──────────────────────────┬─────────────────────────┐
│ Token trend (7d)         │ Feature breakdown        │
│ [line chart]             │ [pie: chat/comp/agent]   │
└──────────────────────────┴─────────────────────────┘
┌───────────────────────────────────────────────────┐
│ Recent interactions (last 10, model + tokens)     │
└───────────────────────────────────────────────────┘
```

### 3.2 Context Window Panel (`panel=context`)

Layout:
```
┌──────────────────────────────────────────────────┐
│  Current Model: claude-sonnet-4-20250514         │
│                                                  │
│  Context Window Utilization                      │
│  ████████████████░░░░░░░░░░░░  54%  (est.)      │
│  ~55,100 / 128,000 tokens                        │
│                                                  │
│  Composition Breakdown:                          │
│  System instructions  ~2,400 tok  ████           │
│  Open file context   ~48,000 tok  ████████████   │
│  Chat history         ~4,700 tok  █████          │
│                                                  │
│  ⚠ 12 tabs open — consider closing unused files  │
│                                                  │
│  max_prompt: 128,000  |  context_window: 200,000 │
│  Gap: 72,000 tokens not accessible via Copilot   │
└──────────────────────────────────────────────────┘
```

Data update frequency: every 5 seconds when panel is visible.

### 3.3 Tokens & Cost Panel (`panel=tokens`)

Sections:
- KPI tiles: Today / 7d / 30d / Month (input tokens, output tokens, total cost)
- Stacked bar chart: daily input vs output vs cache tokens (last 30 days)
- Budget gauge: circular progress vs monthly budget
- Monthly projection based on rolling 7-day average
- Model breakdown table

### 3.4 Cache Panel (`panel=cache`)

Sections:
- Cache Hit Rate gauge (today / 7d / 30d)
- Donut chart: `cache_read` vs `cache_creation` vs `fresh_input` vs `output`
- Trend line: cache hit rate last 30 days
- Estimated savings section (cost difference vs no-cache scenario)
- Recommendations list (auto-generated from `CacheAnalysisService`)

### 3.5 Performance Panel (`panel=performance`)

Sections:
- TTFT histogram (last 100 interactions)
- Latency by model comparison bar chart
- Error rate trend (token limit exceeded, rate limit, timeout)
- finish_reason distribution (stop / length / tool_calls / error)
- Degradation alerts log

### 3.6 Models Panel (`panel=models`)

Sections:
- Model usage distribution pie chart (by token count, last 30d)
- Comparison table:

| Model | Context | Max Prompt | Premium | Avg TTFT | Acceptance | Cost/1K |
|-------|---------|------------|---------|----------|------------|---------|
| ...   | ...     | ...        | ...     | ...      | ...        | ...     |

- Recommendation engine: "For tasks under 5K tokens, use GPT-4o (2x cheaper)"
- "Current model gap" section: shows difference between context_window and max_prompt

---

## 4. Settings Page

**Class:** `CopilotMonitorConfigurable` implements `Configurable`

Sections:

### General
- `[ ] Show token usage in status bar`
- `Monthly token budget: [________] tokens`
- `Monthly cost budget: $[______]`
- `Alert at: [75%] [90%] [100%] of budget`
- `Alert cooldown: [30] minutes`

### Context Window
- `[ ] Show context window warning notifications`
- `Warning threshold: [50]%`
- `Critical threshold: [80]%`

### Cache
- `[ ] Show cache recommendations`
- `Low cache hit rate alert below: [30]%`

### Export (Opt-in)
- `[ ] Enable OpenTelemetry export`
- `OTLP endpoint: [http://localhost:4317]`
- `Protocol: [ HTTP ] [ gRPC ]`
- `[ ] Include content (prompts/responses) — PRIVACY RISK`

### Data
- `Data retention: [90] days`
- `[Export to CSV] [Export to JSON] [Clear all data]`
- `Database location: ~/.../copilot-monitor/metrics.db [Open folder]`

---

## 5. Notifications

Use IntelliJ `NotificationGroup` with `BALLOON` display type:

```kotlin
// In plugin.xml
<extensions defaultExtensionNs="com.intellij">
  <notificationGroup id="CopilotMonitor.Alerts"
                     displayType="BALLOON"
                     toolWindowId="Copilot Monitor"/>
</extensions>
```

```kotlin
// Alert examples
NotificationGroupManager.getInstance()
    .getNotificationGroup("CopilotMonitor.Alerts")
    .createNotification(
        "Copilot Budget Alert",
        "You've used 90% of your monthly token budget (~112,000 / 125,000 tokens).",
        NotificationType.WARNING
    )
    .addAction(ActionManager.getInstance().getAction("CopilotMonitor.OpenDashboard"))
    .notify(project)
```

Notification types by event:

| Event                        | Type    | Action                     |
|------------------------------|---------|----------------------------|
| Budget 75%                   | WARNING | Open Tokens panel          |
| Budget 90%                   | WARNING | Open Tokens panel          |
| Budget 100%                  | ERROR   | Open Tokens panel          |
| Context window >80%          | WARNING | Open Context panel         |
| Token limit exceeded (error) | ERROR   | Suggest model change       |
| Cache hit rate < threshold   | INFO    | Open Cache recommendations |
| Rate limit hit               | WARNING | Show retry guidance        |
