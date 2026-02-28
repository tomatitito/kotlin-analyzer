# Fix: Diagnostics Disappear When Switching Files

## Problem

Diagnostics are only visible for the file currently being viewed. When switching to a clean file, the diagnostics from the previous file disappear. They should persist across all open files.

## Current Architecture

The diagnostic flow is:

1. `didOpen` → immediate `analyze_document(uri)` → `publishDiagnostics(uri, diags)`
2. `didChange` → debounced (300ms) → `analyze_document(uri)` → `publishDiagnostics(uri, diags)`
3. `didClose` → `publishDiagnostics(uri, Vec::new())` (clears diagnostics)

Diagnostics are **not cached** in the server. Each analysis computes fresh diagnostics and publishes them for that single URI. No other files are re-analyzed.

## Root Cause Candidates

### Candidate A: `didClose` clears diagnostics on tab switch

Zed may send `textDocument/didClose` when a tab loses focus or is hidden, followed by `textDocument/didOpen` when it regains focus. The `didClose` handler publishes empty diagnostics, clearing them from the editor's problem list.

### Candidate B: Zed sends `didOpen` only for the focused file

If Zed only tracks the focused document as "open," switching tabs means only one file has diagnostics at a time.

### Candidate C: Race condition in re-analysis

Opening a new file triggers analysis for that file. If the sidecar's `updateFile` call somehow invalidates the previous file's state, cross-file resolution changes could cascade.

## Investigation Plan

### Phase 1: Observe the Protocol (~15 min)

- [ ] **1.1** Open a Kotlin project in `my-zed` with terminal visible
- [ ] **1.2** Open a file with known errors (e.g., `TypeMismatch.kt`), confirm diagnostics appear
- [ ] **1.3** Switch to a clean file and observe the JSON-RPC messages in the `my-zed` terminal. Specifically look for:
  - Does Zed send `textDocument/didClose` for the error file?
  - Does the server send `publishDiagnostics` with empty diagnostics for the error file?
  - What is the exact sequence of messages during the tab switch?
- [ ] **1.4** Document the exact message sequence observed

### Phase 2: Identify Root Cause (~15 min)

Based on Phase 1 findings:

- [ ] **2.1** If `didClose` is the culprit: check whether Zed is sending close notifications on tab switch or only on actual file close
- [ ] **2.2** If no `didClose` is sent: check whether the server is overwriting diagnostics (e.g., publishing for the wrong URI)
- [ ] **2.3** Check whether diagnostics are being published with `version: None` vs a specific version — some editors discard diagnostics if the version doesn't match

### Phase 3: Implement Fix

#### If Candidate A (`didClose` clears diagnostics):

- [ ] **3.1** Add a diagnostic cache to `DocumentStore` — store the latest diagnostics per URI alongside text/version
- [ ] **3.2** On `didClose`, do NOT publish empty diagnostics immediately. Instead, keep diagnostics cached for files that are part of the workspace
- [ ] **3.3** Only clear diagnostics when a file is truly removed from the workspace (deleted, excluded) or when the server shuts down
- [ ] **3.4** On `didOpen`, re-publish cached diagnostics immediately (before triggering fresh analysis), so diagnostics appear instantly when switching back to a file

#### If Candidate B (Zed tracks only focused file):

- [ ] **3.5** After analyzing any file, also re-analyze all other open files that might be affected by cross-file changes
- [ ] **3.6** Alternatively, implement workspace-wide diagnostics: after any change, re-analyze all open documents and publish diagnostics for each

#### If Candidate C (race condition):

- [ ] **3.7** Serialize analysis requests to prevent concurrent modifications
- [ ] **3.8** After analyzing a newly opened file, re-publish cached diagnostics for all other open files to ensure they weren't invalidated

### Phase 4: Verify (~10 min)

- [ ] **4.1** Open a file with errors, confirm diagnostics appear
- [ ] **4.2** Switch to a clean file, confirm previous file's diagnostics persist in the problems panel
- [ ] **4.3** Switch back, confirm diagnostics are still there
- [ ] **4.4** Edit the error file to fix the error, confirm diagnostics clear
- [ ] **4.5** Check `my-zed` logs to confirm no spurious empty `publishDiagnostics` calls

## Key Files

| File | What to modify |
|------|---------------|
| `server/src/server.rs` ~line 758 | `didOpen` handler — may need to re-publish cached diagnostics |
| `server/src/server.rs` ~line 808 | `didClose` handler — likely the culprit, publishes empty diagnostics |
| `server/src/server.rs` ~line 43 | `analyze_document` — may need to cache results |
| `server/src/state.rs` | `DocumentStore` — add diagnostic caching |

## Notes

- The `my-zed` dev build prints LSP messages to the terminal, making Phase 1 straightforward
- The LSP spec says `publishDiagnostics` is cumulative per URI — publishing for URI A does not affect URI B. So if diagnostics disappear for A when opening B, something must be explicitly clearing A's diagnostics.
