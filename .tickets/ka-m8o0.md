---
id: ka-m8o0
status: open
deps: []
links: []
created: 2026-04-07T08:46:23Z
type: task
priority: 2
assignee: Jens Kouros
tags: [kotlin, completion, lsp, sidecar, gradle]
---
# Make Kotlin completion source-set-aware for main vs test

Kotlin completion currently flattens Gradle source roots from all source sets into one project model and one sidecar source module. This allows test-only declarations to appear while editing main-source files and also lets test-only dependencies/classpath leak into the default completion space. Implement source-set-aware completion visibility so files under src/main do not see unrelated test-only declarations, while files under src/test continue to see both main and test declarations.

Current findings:
- Gradle project extraction iterates all source sets and emits a flat sourceRoots list.
- Rust forwards the flat source root list directly to the sidecar.
- The sidecar builds a single source module from all effective roots.
- The symbol index is rebuilt from all discovered session files and has no source-set filtering.
- Existing fixtures already include merged main/test source roots and test libraries.

Intended visibility rules:
- Files under src/main: locals, same-file/package symbols, main-source declarations, and main dependencies only. No test-source declarations or test-only dependencies.
- Files under src/test: main plus test declarations and test dependencies remain available.

Implementation direction:
- Carry source-set metadata through the project model instead of only a flat sourceRoots list.
- Enforce requester-aware visibility in completion result handling for both Analysis API scope results and symbol-index-backed unimported symbols.
- Prefer a single long-lived session unless the Analysis API boundary makes source ownership impossible to determine reliably; if so, split deeper module/session partitioning into a follow-up.

Suggested validation:
- Add a repro matrix covering src/main vs src/test, project-local declarations, and unimported symbol completion.
- Confirm that main-source completion no longer includes test-only declarations and that test-source completion still includes main + test symbols.

## Acceptance Criteria

- Completion requested from files under src/main does not surface unrelated test-only declarations.
- Completion requested from files under src/test continues to surface appropriate test-only declarations alongside main-source declarations.
- Symbol-index-backed unimported completion respects the same source-set visibility rules.
- Project model or sidecar metadata is sufficient to determine requester source-set context.
- Regression tests cover src/main vs src/test completion visibility, including project-local and unimported symbol candidates.
