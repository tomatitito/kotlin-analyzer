---
id: ka-3ug3
status: open
deps: []
links: []
created: 2026-05-05T11:39:03Z
type: bug
priority: 2
assignee: Jens Kouros
parent: ka-sizl
---
# Deduplicate redundant view facts in extractViewFacts

extractViewFacts produces two facts for the same Rendering template: one from the return expression (KtDotQualifiedExpression spanning the whole chain) and one from the nested view() KtCallExpression. They have different ranges so distinctBy doesn't dedup them.

This is harmless (wider range subsumes narrower in lookups) but means the index carries redundant entries. Either filter KtCallExpression facts whose parent DQE was already processed, or accept and document the redundancy.
