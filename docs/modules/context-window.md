# Module: Context Window Monitor

## Purpose

Provide real-time visibility into how much of the Copilot LLM's effective context
window is being consumed, and warn the developer before they hit the limit.

## Key Problem

GitHub Copilot enforces a `max_prompt` limit (effective limit) that is often
significantly lower than the model's theoretical `context_window`. Example:

| Model               | context_window | max_prompt (Copilot) | Gap        |
|---------------------|----------------|----------------------|------------|
| GPT-4o              | 128K           | 64K                  | 64K unused |
| Claude Sonnet 4     | 200K           | 128K                 | 72K unused |
| GPT-5.2-codex       | 400K           | 400K                 | 0 (full)   |
| GPT-4.1             | 1M             | 128K                 | 872K unused|

Developers hitting `token limit exceeded` errors are often unaware of this gap.

## Implementation Tasks

- [ ] **Task CW-1:** Create `ContextWindowService` as `ProjectService`
  - Inject `FileEditorManager`, `ProjectManager`
  - Expose `fun getCurrentStatus(): ContextWindowStatus`
  - Use `PluginCoroutineScope` for background refresh every 5s
  
- [ ] **Task CW-2:** Implement token estimation
  - Formula: `estimatedTokens = totalOpenFileChars / 3.5`
  - Account for: open file content + selection + estimated system prompt (~2K fixed)
  - Label all estimates with `isEstimated = true`
  
- [ ] **Task CW-3:** Implement `ModelConfigRepository`
  - Load from SQLite `model_config` table
  - Seed initial data from `SEED_MODELS` on first install
  - Provide `fun getMaxPrompt(modelId: String): Long`
  
- [ ] **Task CW-4:** Parse `max_prompt` from Copilot API response headers
  - Watch for `x-copilot-max-prompt` or equivalent response header in logs
  - Update `model_config` when new values are observed
  
- [ ] **Task CW-5:** Detect `token limit exceeded` errors in logs
  - Pattern: `"Oops, the token limit exceeded"` or `model_max_prompt_tokens_exceeded`
  - When detected: fire `ContextWindowWarning.EXCEEDED` event
  - Record in `alerts` table
  
- [ ] **Task CW-6:** Context panel webview
  - Circular progress bar with color gradient (green → yellow → red)
  - Breakdown bars for each context component
  - Update via bridge every 5s when panel is visible
  
- [ ] **Task CW-7:** Recommendations engine
  ```kotlin
  fun generateRecommendations(status: ContextWindowStatus): List<String> {
      val recs = mutableListOf<String>()
      if (status.openTabCount > 5) recs.add("Close unused tabs to free ~${status.openTabCount * 500} tokens")
      if (status.utilizationPct > 80) recs.add("Consider switching to a model with larger max_prompt")
      if (status.utilizationPct > 80) recs.add("Use /compact or start a new chat session to reset context")
      return recs
  }
  ```

## Context Composition Estimation

```
Total estimated tokens = sum of:
  [A] System prompt (copilot instructions)   ~2,000–5,000 tokens (fixed, model-dependent)
  [B] Open file contents                     chars / 3.5
  [C] Current selection / cursor context     chars / 3.5
  [D] Chat history (from session log)        tracked from session log if available
  [E] Tool outputs (in agent mode)           tracked from session log if available
```

When session log data is available for [D] and [E], use actual values.
Otherwise use 0 with a note in UI: "Chat history tokens unavailable".

## Events

```kotlin
// Publish when threshold crossed (debounced: only once per 5-minute window per level)
data class ContextWindowWarningEvent(
    val warning: ContextWindowWarning,
    val utilizationPct: Double,
    val model: String,
    val recommendations: List<String>
)
```
