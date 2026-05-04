---
id: ka-4qf5
status: open
deps: []
links: [ka-pg9l, ka-3sg2]
created: 2026-05-04T12:33:59Z
type: epic
priority: 2
assignee: Jens Kouros
tags: [kotlin, imports, lsp, code-actions, zed]
---
# Auto-import for unresolved types in Kotlin type annotations

Epic covering all work to automatically resolve unresolved type references in Kotlin type annotations. Splits the original ka-3sg2 into focused deliverables with clear dependencies.

Two distinct mechanisms:
1. Code-action imports: extend the existing UNRESOLVED_REFERENCE quickfix path to cover type-position annotations (property types, function return types). Low risk, builds on existing infrastructure.
2. Post-edit auto-import: after re-analysis of a changed document, automatically insert the import when a single unique candidate exists. Requires a working workspace/applyEdit transport and debounce/staleness strategy.

Supersedes ka-3sg2.
