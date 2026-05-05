---
id: ka-36lk
status: open
deps: []
links: []
created: 2026-05-05T15:25:16Z
type: epic
priority: 1
assignee: Jens Kouros
tags: [perf, completion]
---
# Fix unusable auto-completion latency

Auto-completion works but is unusably slow. Root cause: every completion request triggers a full Kotlin compiler session rebuild because `sessionDirty` is always true (set by every keystroke via `updateFile()`). This compounds with eager type resolution for all candidates and single-threaded request processing. Goal: bring completion latency from 1-3 seconds to <300ms.
