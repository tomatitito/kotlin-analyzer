---
id: ka-rd06
status: open
deps: [ka-rd02]
links: [ka-ybyr, ka-rd05, ka-slua]
created: 2026-05-05T20:32:00Z
type: task
priority: 1
assignee: Jens Kouros
parent: ka-36lk
tags: [perf, correctness, sidecar, tests, psi, fir]
---
# Add stale and duplicate PSI/FIR visibility regression tests

Create focused regression coverage for the stale/duplicate file failure modes that can appear when physical files, shadow-root files, LightVirtualFile-backed files, and dangling files coexist.

## Scope

- Reproduce a case where FIR sees stale session content after editor text changes.
- Reproduce or guard against duplicate declarations when both a physical/shadow file and an updated virtual/dangling file are visible.
- Cover navigation URI mapping so shadow/internal files are not returned to the editor.
- Cover completion/diagnostics after ordinary edits to existing files.

## Acceptance Criteria

- Tests fail against at least one known unsafe/stale behavior or explicitly lock down a previously fixed behavior.
- Tests distinguish editor-facing URI expectations from internal shadow/temp paths.
- Tests cover completion and diagnostics after an ordinary edit without requiring a full rebuild.
- The tests become gates for implementing `ka-rd03`, `ka-rd04`, and `ka-rd05`.

## Notes

**2026-05-05T21:37:28Z**

ka-rd02 design decision: regression gates must cover duplicate FIR visibility, stale editor text reads, shadow/temp URI leaks, obsolete-version diagnostics publication, restart replay of open-buffer overlays, and source-set-wide request behavior when one focused file is EditorOverlayDirty.
