---
id: ka-fm0u
status: open
deps: []
links: [ka-3sg2]
created: 2026-04-04T16:22:50Z
type: bug
priority: 2
assignee: Jens Kouros
tags: [kotlin, imports, lsp, tests, regression]
---
# Fix flaky and incorrect Rust integration coverage for post-edit auto-import applyEdit

The current Rust integration attempt for post-edit auto-import applyEdit is not suitable to commit as-is. The failing test expected the import edit at line 2 even though the current implementation inserts it at line 1 after the package declaration. More importantly, it tried to capture workspace/applyEdit during a later hover request even though the edit is emitted by the debounced analyze path after didChange. Add stable Rust-side coverage that observes the actual applyEdit request produced by post-edit analysis, or document that sidecar-level coverage is sufficient if server transport is unchanged.

## Acceptance Criteria

A Rust/server regression test covers post-edit auto-import edit delivery without relying on a later unrelated request or arbitrary sleeps.
The test asserts the real workspace/applyEdit payload emitted for the current document version.
The expected import insertion range matches the actual server behavior.
The coverage complements, rather than duplicates, existing sidecar auto-import tests.
