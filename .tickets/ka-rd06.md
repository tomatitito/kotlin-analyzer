---
id: ka-rd06
status: in_progress
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

**2026-05-06T08:26:08Z**

Refinement for implementation: start with JVM sidecar integration tests, not Rust LSP tests. Use existing test-sources fixtures and bridge.updateFile(...) overlays so tests exercise the same Analysis API/PSI selection paths as completion, diagnostics, definition, and URI mapping. Prefer observable assertions and metrics/reason fields over private implementation checks.

Concrete scenarios to cover:
1. Existing source overlay completion: edit an existing session file to add a local symbol and assert completion sees the edited symbol with sessionRebuilt=0, danglingFallback=0, and completionFileMode=existing_session_source_with_editor_overlay.
2. Duplicate FIR/PSI visibility guard: create/edit content that would expose duplicate declarations if stale session PSI and current virtual/dangling PSI were both FIR-visible; assert no duplicate completion labels or duplicate redeclaration diagnostics are produced for the focused file.
3. Diagnostics stale-text guard: edit a clean existing file to introduce a type mismatch and assert diagnostics either report the edited-text error for the requested version or return an explicitly documented stale-safe/deferred result; never silently report stale clean success as if it represented current editor text.
4. URI mapping guard: for dirty existing files and any shadow/temp/dangling path involved internally, assert editor-facing diagnostics/definition/reference locations map back to the original file URI, not shadow or temp paths.
5. New virtual file distinction: updateFile for a file not currently in the session source set and assert it is marked/handled as new_virtual_source_not_in_session or source-set-dirty behavior, not as an existing-session editor overlay.

Acceptance clarification: tests may lock down already-fixed behavior. If a scenario exposes an existing bug that is too large for this ticket, document the exact failing behavior in a ticket note and create/link a follow-up; otherwise fix it in ka-rd06. Diagnostics behavior can be either current-overlay diagnostics or explicit stale-safe deferral, but the test must make that policy visible and prevent stale success from masquerading as current analysis.
