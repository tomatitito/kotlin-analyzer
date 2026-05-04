---
id: ka-xc8r
status: open
deps: [ka-n091]
links: [ka-n091]
created: 2026-04-07T09:22:09Z
type: task
priority: 2
assignee: Jens Kouros
tags: [kotlin, completion, lsp, sidecar]
---
# Add fuzzy matching for non-member Kotlin scope completion

Non-member Kotlin scope completion should eventually support fuzzy matching so useful symbols can surface even when the typed prefix is incomplete, out of order, or only partially aligned with the symbol label. Build this as a follow-up to the exact/prefix-oriented ranking pipeline introduced in ka-n091.

Scope:
- Apply to non-member/scope completion only.
- Reuse the matcher/scorer stage introduced by ka-n091 rather than reworking candidate collection.
- Keep member completion unchanged unless separate evidence justifies broadening.

Implementation direction:
- Add a fuzzy-match scoring bucket to the non-member scope completion matcher/scorer pipeline.
- Preserve existing source-priority rules: visible in-scope symbols ahead of keywords, and auto-import candidates after already-visible in-scope symbols unless a later design explicitly changes that policy.
- Use deterministic scoring and tie-breakers so ranking remains stable across runs and testable.
- Define the fuzzy policy explicitly for empty prefix, weak fuzzy matches, and ties between strong prefix matches and fuzzy matches.

Suggested validation:
- Add integration coverage showing fuzzy-friendly queries still surface useful stdlib top-level functions and project-local declarations without regressing exact/prefix cases.
- Confirm weak fuzzy matches do not overwhelm stronger exact/prefix matches or reorder auto-import candidates ahead of visible in-scope symbols.

## Acceptance Criteria

- Non-member scope completion supports a fuzzy-match scoring bucket in the matcher/scorer stage introduced by ka-n091.
- Strong exact/prefix matches still outrank weaker fuzzy matches.
- Useful fuzzy matches can surface relevant visible in-scope symbols for partially aligned queries without regressing deterministic ordering.
- Member completion behavior is unchanged.
- Existing auto-import completion still works and remains ordered after already-visible in-scope results unless explicitly designed otherwise.
- Regression tests cover fuzzy matching for non-member scope completion and preserve exact/prefix ranking expectations.
