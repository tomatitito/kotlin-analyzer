---
id: ka-crn4
status: open
deps: []
links: []
created: 2026-04-02T10:15:38Z
type: bug
priority: 1
assignee: Jens Kouros
tags: [completion, zed, lsp, debugging]
---
# Completion popup never appears in Zed

The analyzer advertises completion support and has integration tests for `textDocument/completion`, but in real Zed use no completion popup appears at all. This is a pipeline availability bug, not a greenfield completion feature request.

We need to identify where completion breaks between Zed, the Rust LSP server, and the JVM sidecar, then fix the first failing hop. The implementation should start with an end-to-end reproduction in `my-zed`, because isolated server and sidecar tests already show completion working in controlled conditions.

Known relevant context in-tree:
- `server/src/server.rs` advertises `completionProvider` and forwards completion requests.
- `sidecar/src/main/kotlin/dev/kouros/sidecar/CompilerBridge.kt` implements completion.
- `server/tests/integration_test.rs::test_completion_works` proves the request path works in isolation.
- `plans/active/sidecar-reliability.md` documents failure modes that can make completion appear dead in editor use, especially sidecar blocking, full session rebuilds after edits, URI/path handling, and silent empty responses.

## Design

Investigation and implementation should proceed in this order:

1. Reproduce in `my-zed` and capture logs while typing `.` and while invoking completion manually. Confirm whether Zed sends `textDocument/completion` at all.
2. If Zed sends the request, verify the Rust server receives it, forwards it, and returns a valid completion response.
3. If the sidecar is slow or unavailable, fix the relevant readiness/blocking failure mode so completion returns in time for Zed to render a popup.
4. Add targeted logging for completion failures instead of silently returning empty results.
5. Add a regression test for the reproduced failure mode and document the manual Zed verification flow.

Likely root-cause areas:
- Startup/readiness race between Zed requests and sidecar availability
- Sidecar blocking during `analyzeAll` or session rebuilds after edits
- URI or workspace-file resolution problems in real projects
- Response-shape or capability issues that prevent Zed from surfacing results even when the backend has them

## Acceptance Criteria

- Typing `.` in a Kotlin file in Zed produces a visible completion popup.
- Manual completion invocation in Zed also produces a popup.
- `my-zed` logs show a successful `textDocument/completion` request/response round-trip for the reproduced scenario.
- Completion works in a real Gradle or Maven workspace, not only in isolated integration tests.
- Failures are logged with an explicit reason instead of silently returning empty completion results.
- At least one regression test covers the identified failure mode.
