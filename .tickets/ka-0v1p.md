---
id: ka-0v1p
status: closed
deps: []
links: []
created: 2026-04-15T11:50:48Z
type: bug
priority: 1
assignee: Jens Kouros
tags: [lsp, definition, zed, workspace, path-resolution]
---
# Go to definition opens shadow-root file instead of workspace file

Observed behavior:
- Triggering go to definition opens a file, but the opened file is not the real file from the project workspace.
- Instead, Zed opens the corresponding file from the shadow root.

Expected behavior:
- Go to definition should resolve to and open the real source file that belongs to the codebase/workspace, not the shadow-root copy.

Impact:
- Navigation lands in an unexpected file outside the real codebase view.
- Follow-up edits and navigation can happen against the wrong file representation.

Notes:
- The bug appears to be in definition target URI/path selection or translation before the location is returned to the editor.

## Acceptance Criteria

- Reproduce the issue with a project symbol whose definition exists in the workspace.
- Go to definition returns a URI/path for the real workspace file, not the shadow-root file.
- The editor opens the real codebase file after navigation.
- Add or update automated coverage for the URI/path translation logic that previously preferred the shadow-root location.


## Notes

**2026-04-15T11:52:41Z**

Inspection findings:
- Root cause is likely in sidecar URI construction for source navigation results, not in the Rust LSP frontend. `server/src/server.rs` `parse_locations()` just parses the returned URI and forwards it unchanged to Zed.
- The sidecar intentionally builds a shadow source tree and records a reverse map from shadow path -> workspace path in `BridgeContext.shadowPathMapping` (`sidecar/src/main/kotlin/dev/kouros/sidecar/BridgeContext.kt`).
- There is already a helper that remaps session/shadow files back to workspace URIs: `fileUriForKtFile()` in `BridgeContext.kt`.
- `textDocument/definition` does not currently use that helper for source PSI targets. In `sidecar/src/main/kotlin/dev/kouros/sidecar/BridgeNavigation.kt`, `buildPsiDefinitionLocation()` builds the target URI directly from `file.virtualFile.path` via `ctx.buildFileUri(vfPath)`. When the target PSI comes from the shadow tree, this returns the shadow-root URI to the editor.
- Same pattern also exists in `implementation()` / `findImplementingMemberLocations()`, so the bug may affect go-to-implementation too, not just definition.

Refinement proposal:
- Scope this ticket to fixing source-navigation URI remapping for definition targets produced from shadow-session files.
- Prefer `ctx.fileUriForKtFile(...)` (or an equivalent helper that remaps `shadowPathMapping`) whenever a source `KtFile` / PSI-backed location is converted into an editor-facing URI.
- Keep library/decompiled targets unchanged.

Suggested test coverage to add:
- Reproduction test where a workspace file is opened through the shadow session (e.g. after `updateFile()` with same-as-disk or modified content) and `definition()` must still return the real workspace URI.
- Regression coverage for PSI-based resolution path, not only symbol-based path.
- Optional adjacent test for `implementation()` because it appears to construct source URIs the same unsafe way.
