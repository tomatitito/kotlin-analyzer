---
id: ka-l6wu
status: closed
deps: [ka-38f0]
links: []
created: 2026-04-15T07:56:27Z
type: task
priority: 1
assignee: Jens Kouros
parent: ka-ixtv
tags: [diagnostics, tests, rust]
---
# Add regression tests for diagnostic cache lifecycle

Add tests around diagnostic caching, replay, and background analysis so we catch stale-diagnostics regressions early.

## Acceptance Criteria

- There is a test that simulates a file first producing diagnostics and then becoming clean.
- The test asserts stale diagnostics are cleared and not republished on reopen.
- Diagnostic cache lifecycle behavior is covered at the appropriate unit/integration level.
