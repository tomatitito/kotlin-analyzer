---
id: ka-kyvv
status: open
deps: []
links: []
created: 2026-05-05T15:25:25Z
type: task
priority: 0
assignee: Jens Kouros
parent: ka-36lk
tags: [perf, completion]
---
# Stop rebuilding compiler session during completion

Every keystroke sets `sessionDirty = true` in `updateFile()` (BridgeContext.kt:273-296). The completion entry point (BridgeCompletion.kt:47-50) calls `ensureSessionCurrent()` which triggers a full `initialize()` — rebuilding shadow source roots, Analysis API session, symbol index, and classpath FQN index. This costs 100-3000ms and is the primary bottleneck.

Fix: remove the `ensureSessionCurrent()` call from the completion path. The virtual file content is already up-to-date in `virtualFiles`, and `analyze(ktFile)` at line 100 uses the current PSI. The session rebuild adds no value for completion — it only matters for diagnostics (which already run on their own debounced path).

## Acceptance Criteria

- Completion no longer calls ensureSessionCurrent()
- Completion latency drops by 100-3000ms (verify via [PERF] log)
- Diagnostics still work correctly after edits
- Completion results are still accurate for the current buffer content
