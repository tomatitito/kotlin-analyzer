---
id: ka-rd05
status: open
deps: [ka-rd02, ka-rd03]
links: [ka-hexa, ka-rd06, ka-slua]
created: 2026-05-05T20:32:00Z
type: task
priority: 1
assignee: Jens Kouros
parent: ka-36lk
tags: [perf, sidecar, rust, diagnostics, scheduling]
---
# Implement explicit rebuild and background-work policy

Replace ad hoc session dirtiness and background analysis scheduling with an explicit policy for when rebuilds are allowed and how background diagnostics yield to interactive requests.

## Scope

- Centralize full-session rebuild triggers.
- Treat ordinary text edits separately from structural/project changes.
- Ensure background project diagnostics do not monopolize the single sidecar request lane.
- Decide whether to refine, replace, or close `ka-hexa` based on the new file/session model.
- Make cancellation/defer behavior visible in metrics/logs.

## Acceptance Criteria

- Full `StandaloneAnalysisAPISession` rebuilds happen only for documented structural/project changes.
- Background diagnostics are deferable or cancellable around completion/hover/signature-help requests.
- Existing diagnostics behavior remains correct after saves and tab switches.
- The implementation includes tests or metrics proving ordinary completion is not queued behind unnecessary rebuild/background analysis work.
- `ka-hexa` is updated, superseded, or closed with a note explaining the relationship.

## Notes

**2026-05-05T21:37:28Z**

ka-rd02 design decision: replace sessionDirty with typed freshness dimensions: Missing, Current, EditorOverlayDirty(uris), SourceSetDirty(uris/reason), ProjectModelDirty(reason), plus diagnostics version dirtiness as needed. Centralize rebuild authorization so completion/hover/signature help and ordinary-edit diagnostics cannot synchronously rebuild the full session.
