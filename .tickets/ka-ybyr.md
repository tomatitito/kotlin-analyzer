---
id: ka-ybyr
status: closed
deps: []
links: []
created: 2026-05-05T08:38:09Z
type: bug
priority: 1
assignee: Jens Kouros
external-ref: gh-57
tags: [navigation, shadow-root, zed]
---
# Go-to definition still opens files from shadow root

Go-to declaration/definition still opens Kotlin source files from the sidecar shadow root instead of the original workspace path. We recently changed navigation so files should not be opened from shadowRoot when double-clicking navigation targets or using go-to declaration/definition, but the go-to path still leaks a shadow-root URI in Zed.

Observed:
- Trigger go-to declaration/definition from a Kotlin source file.
- The target opens under the sidecar shadow tree/temp directory instead of the original workspace file.
- Edits/navigation can land in the generated shadow copy rather than the actual project file.

Expected:
- All editor-facing navigation targets for project sources resolve to the original workspace file:// URI.
- Shadow-root paths remain internal to the Analysis API implementation and are never returned to the editor for project files.

Notes / likely areas:
- BridgeContext.fileUriForKtFile() maps KtFile.virtualFile.path through shadowPathMapping.
- BridgeNavigation.kt has multiple location builders; verify every definition path uses the mapping and no fallback still calls buildFileUri(vfPath) for shadow files.
- Existing test coverage includes definition - returns workspace URI not shadow root, but the real editor path still reproduces, so add/adjust an integration/e2e case matching the failing go-to scenario.

## Acceptance Criteria

- Go-to declaration/definition from Zed opens the workspace source file, not a shadow-root file.
- Double-click navigation targets also open workspace source files.
- Returned LSP locations for project sources never contain /shadow/ or the sidecar temp root.
- Regression test covers the path that currently leaks the shadow URI.


## Notes

**2026-05-05T08:45:53Z**

Implemented workspace URI mapping for shadow-backed navigation paths; added regression coverage for definition/implementation after shadow rebuild. Targeted tests pass; full sidecar test run blocked by Gradle fixture cache metadata errors.
