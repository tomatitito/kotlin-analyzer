---
id: ka-rd03
status: open
deps: [ka-rd02, ka-rw1x]
links: [ka-kyvv]
created: 2026-05-05T20:32:00Z
type: task
priority: 0
assignee: Jens Kouros
parent: ka-36lk
tags: [perf, completion, sidecar, psi]
---
# Prototype dangling PSI for interactive completion

Prototype a completion path that analyzes current editor text through a temporary/dangling PSI file against the stable project/module context, without forcing a full session rebuild for ordinary edits to existing source files.

## Scope

- Apply first to `completion` only.
- Keep full session rebuild behavior for new/deleted source files and project model changes.
- Avoid relying on stale on-disk PSI when current editor text differs.
- Investigate whether Kotlin Analysis API dangling-file resolution modes can replace the current validation bypass and shadow rebuild dependency for completion.
- Preserve existing completion behavior as a fallback if dangling PSI fails.

## Implementation Plan

1. Add an explicit completion file-selection helper in the sidecar, separate from the generic `findKtFile(...)` path:
   - Locate the stable session `KtFile` for `uri` using the current `StandaloneAnalysisAPISession`.
   - Detect whether `virtualFiles[uri]` has current editor text that differs from the stable session/on-disk text.
   - Classify the request as one of:
     - existing session source file with editor overlay;
     - existing session source file without editor overlay;
     - new virtual/source file not present in the session source set;
     - missing/no-session.
2. For existing session files with editor-overlay text, prototype a dangling PSI file:
   - Create a temporary `KtFile` from the current editor text, likely through `KtPsiFactory.createFile(...)`.
   - Set the temporary file's `originalFile` to the stable session file when the API permits it.
   - Try Analysis API dangling-file resolution using `KaDanglingFileResolutionMode.IGNORE_SELF` first, because Kotlin LSP uses that mode for completion edit simulation.
   - Keep the mode choice isolated behind a small helper/constant so `PREFER_SELF` can be tested quickly if `IGNORE_SELF` fails or gives stale/self-conflicting results.
   - Do not build a full dual-mode runtime system in this ticket; default to `IGNORE_SELF` and document if `PREFER_SELF` is needed.
3. Run the existing completion implementation against the selected current-text PSI file:
   - `lineColToOffset`, PSI lookup, receiver detection, scope/member collection, import completion, ranking, and item assembly should use the current-text PSI.
   - Project/classpath symbol indexes may remain stable-session indexes.
   - Response URIs and import edits must map back to the editor URI, never a temp/shadow path.
4. Keep fallback behavior explicit:
   - If dangling PSI creation or analysis throws, fall back to the current LightVirtualFile/session path and record the fallback reason in metrics/logs.
   - Fallback should be quiet in normal logs by default; it must be visible when completion metrics are enabled.
   - If the URI is a new source file not present in the session source set, keep the current `newFilesPendingInSession` rebuild behavior for now.
   - Completion must not call `ensureSessionCurrent()` for ordinary edits to an existing session source file.
5. Feed the prototype into the `ka-rw1x` metrics:
   - Add stage/counter fields for `completionFileMode`, dangling creation time, dangling analysis mode tried, and fallback count/reason.
   - Compare warmed sensor output before/after for scenarios that edit an existing source file.

## Concrete Code Areas

- `sidecar/src/main/kotlin/dev/kouros/sidecar/BridgeContext.kt`
  - Add the completion-specific file-selection/dangling-PSI helper.
  - Keep generic `findKtFile(...)` behavior available for non-completion features.
- `sidecar/src/main/kotlin/dev/kouros/sidecar/BridgeCompletion.kt`
  - Use the new helper before `completionOnce(...)`/`analyze(...)`.
  - Keep fallback contained here so other semantic features do not accidentally adopt the prototype.
- `sidecar/src/main/kotlin/dev/kouros/sidecar/CompletionMetrics.kt`
  - Add dangling-mode/fallback counters if needed.
- `sidecar/src/test/kotlin/dev/kouros/sidecar/CompilerBridgeIntegrationTest.kt`
  - Add focused after-edit completion regressions.
- `docs/sidecar-file-session-lifecycle.md` or ticket notes
  - Record any Analysis API limitation, chosen resolution mode, and fallback behavior.

## Test Matrix

Add or update regression coverage for these cases:

- Existing source file, unsaved local declaration edit:
  - Start from a real fixture file already in the session.
  - Send `updateFile(...)` with an added local `val` or function.
  - Completion sees the new local symbol.
  - Metrics/log assertions or observable behavior show no `session_refresh` / `ensureSessionCurrent()` for this ordinary edit.
- Existing source file, edited receiver/member completion:
  - Edit current text so the receiver expression or typed prefix differs from disk.
  - Member completion uses the edited text and still returns expected classpath/project members.
- Existing source file, prefix/scope completion:
  - Scope completion still includes locals, file declarations, imported symbols, and classpath/project candidates covered by current tests.
- Existing source file, import completion:
  - Import directive completion still returns package/type candidates and constructs import edits correctly where applicable.
- Stale disk avoidance:
  - Create a case where disk/session text would not contain the symbol under the cursor but editor text does.
  - Completion must follow editor text.
- Fallback path:
  - If it is practical to induce dangling PSI failure in a controlled test, assert fallback still returns a valid completion response and records fallback reason.

## Acceptance Criteria

- Ordinary edits to an existing source file do not force `ensureSessionCurrent()` before completion.
- Completion uses current editor buffer text, not stale disk text.
- Completion results remain semantically correct for local declarations, class members, imports, and project/classpath symbols covered by existing tests.
- Add regression coverage for after-edit completion in an existing source file.
- Metrics from `ka-rw1x` show whether the prototype removes rebuild/session-refresh time from the completion path.
- Any Analysis API limitations discovered are documented in the ticket notes or design doc.
- Dangling PSI mode and fallback decisions are visible in metrics or structured logs when completion metrics are enabled.
- The prototype remains completion-only; hover, diagnostics, navigation, rename, code actions, and hierarchy features do not change behavior except through shared helpers that are demonstrably inert for them.
- Tests directly assert that ordinary existing-file completion does not rebuild, preferably through completion metrics (`sessionRebuilt == 0` and no `session_refresh` stage).
- Closing this ticket does not require removing the current PSI validation bypass. It does require documenting whether the dangling completion path still depends on that bypass.

## Non-Goals

- Do not implement the top-level `sensors run` CLI.
- Do not redesign diagnostics freshness or publish dangling diagnostics.
- Do not solve new/deleted/renamed source file invalidation; leave that to `ka-rd04`/`ka-rd05`.
- Do not remove the existing LightVirtualFile/shadow-root fallback until the dangling path is proven correct.
- Do not require removal of the PSI validation bypass in this ticket; if dangling completion works without it, file or note a focused cleanup follow-up.
- Do not broaden the prototype to hover/signature help in this ticket.
- Do not require a real-project benchmark run; keep this ticket fixture/local-project runnable. Real-project execution belongs to `ka-j4dc`.

## Implementation Decisions

- Default dangling-file resolution mode: `KaDanglingFileResolutionMode.IGNORE_SELF`.
- Keep `PREFER_SELF` easy to try during implementation, but only switch to it if `IGNORE_SELF` is proven stale, incomplete, or unusable in standalone sidecar completion.
- PSI validation bypass removal is not part of the close criteria. The implementation must record whether dangling completion still needs the bypass.
- Fallback to the current LightVirtualFile/session path should not warn in normal logs. It should be captured in completion metrics/structured logs when metrics are enabled.
- Sensor comparison for this ticket is fixture/local-project only. Real-project runs are `ka-j4dc`.
- No-rebuild coverage should be a direct assertion when practical: request completion with metrics enabled and assert `counts.sessionRebuilt == 0` plus no `stages.session_refresh` for ordinary existing-file edits.

## Remaining Unknowns To Discover During Implementation

- Whether standalone Analysis API exposes the exact same dangling-file setup used by Kotlin LSP in this dependency set.
- Whether `originalFile` assignment is sufficient to attach module context, or whether an additional Analysis API helper is required.
- Whether `IGNORE_SELF` sees local declarations and edited receivers from the temporary file correctly in all covered completion scenarios.
- Whether the dangling path works with the current PSI validation bypass disabled for completion; if yes, note a follow-up to remove the bypass safely.

## Notes

**2026-05-05T21:37:28Z**

ka-rd02 design decision: completion is the first typed EditorOverlayDirty consumer. For existing source files, prototype current editor text through dangling PSI against the stable module/file context, with the current LightVirtualFile path as fallback. Completion must not synchronously call full session rebuild for ordinary edits; rebuild remains allowed for new files/source-set dirty/project-model dirty states.

**2026-05-06T05:19:55Z**

Refinement: the prototype should follow the Kotlin LSP / Analysis API dangling-file pattern documented in `docs/language-server-performance-research.md`: create a temporary `KtFile`, connect it to the stable original file/module context, and analyze it with `KaDanglingFileResolutionMode.IGNORE_SELF` or `PREFER_SELF`. The implementation should make the selected mode, fallback reason, and rebuild behavior observable through the completion metrics added by `ka-rw1x`.

**2026-05-06T05:28:29Z**

Decision refinement: default to `KaDanglingFileResolutionMode.IGNORE_SELF`; keep `PREFER_SELF` easy to test but do not implement automatic dual-mode behavior unless needed. Do not require removing the PSI validation bypass for this prototype. Fallback should be quiet by default and visible through metrics. Keep benchmark validation fixture-local; `ka-j4dc` owns real-project execution. Add direct no-rebuild assertions using completion metrics where practical.
