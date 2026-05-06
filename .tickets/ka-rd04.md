---
id: ka-rd04
status: open
deps: [ka-rd02]
links: []
created: 2026-05-05T20:32:00Z
type: task
priority: 1
assignee: Jens Kouros
parent: ka-36lk
tags: [perf, sidecar, analysis-api, fir, spike]
---
# Spike FIR/session cache invalidation for changed files

Investigate whether standalone Kotlin Analysis API exposes usable hooks for invalidating FIR/session caches for a specific changed file without rebuilding the full `StandaloneAnalysisAPISession`.

## Scope

- Look for public or internal APIs analogous to Kotlin LSP's LL FIR session cache invalidation.
- Verify whether those APIs are available in the bundled standalone Analysis API artifacts.
- Build a minimal experiment against an existing fixture:
  - initialize session;
  - analyze a file;
  - change file content;
  - invalidate only that file/cache if possible;
  - re-run diagnostics/completion/hover and check whether FIR sees the updated content.
- Record whether the approach is viable, unstable-but-usable, or not feasible in standalone mode.

## Acceptance Criteria

- Spike notes identify the relevant APIs/classes and their availability in the current sidecar runtime.
- A minimal experiment demonstrates success or a concrete blocker.
- The ticket recommends one of:
  - use file-level FIR/session invalidation;
  - use dangling PSI for interactive requests and rebuild only for diagnostics/structural changes;
  - abandon this path due to standalone API limitations.
- Follow-up implementation tickets are created or amended based on the result.

## Notes

**2026-05-05T21:37:28Z**

ka-rd02 design decision: this spike owns the open diagnostics/invalidation question for EditorOverlayDirty. Verify whether standalone Analysis API exposes usable LL FIR/session invalidation analogous to Kotlin LSP; if not, recommend dangling PSI for interactive requests plus debounced rebuild/deferred diagnostics for non-interactive semantic state.
