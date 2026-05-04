---
id: ka-ixtv
status: open
deps: [ka-38f0, ka-l6wu, ka-v3hp, ka-xmsu]
links: []
created: 2026-04-15T07:56:27Z
type: epic
priority: 1
assignee: Jens Kouros
tags: [diagnostics, lsp, sidecar, regression]
---
# Stale diagnostics and Kotlin version/runtime robustness

False-positive diagnostics can persist after the underlying analysis becomes clean, especially across restarts or cached project models. We also need stronger regression coverage for Spring/Kotlin 2.3 projects and better Kotlin version/runtime alignment in the sidecar.

## Acceptance Criteria

- A clear fix exists for stale diagnostic persistence.
- Regression coverage prevents stale diagnostics from silently returning.
- A Spring/Kotlin 2.3 project shape is covered by tests.
- Kotlin version/runtime alignment risks are tracked and improved.
