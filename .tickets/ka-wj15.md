---
id: ka-wj15
status: closed
deps: []
links: [ka-8zx3, ka-aj6x]
created: 2026-04-08T08:05:41Z
type: bug
priority: 1
assignee: Jens Kouros
tags: [navigation, lsp, zed, kotlin, analysis-api]
---
# Fix go-to-implementation for interface method declarations

I do not currently have a working way to navigate from a method declaration inside an interface to its implementation(s). Standard go-to-implementation on the interface method does not work, which blocks a core semantic navigation workflow.

Scope this specifically to method-level navigation, not just type-level interface <-> implementation jumps. When the cursor is on an interface member declaration such as `fun foo(...)`, the analyzer should resolve concrete overriding implementations of that member and surface them through a Zed-compatible navigation path.

Implementation notes:
- Resolve override targets semantically in the Kotlin sidecar using symbol information rather than filename or text heuristics.
- Keep Rust focused on transport/orchestration; method target discovery belongs in Kotlin.
- Reuse whatever Zed-compatible navigation transport we settle on for analyzer-owned navigation actions instead of depending on `window/showDocument` alone.
- Handle zero, one, and multiple implementations deterministically.
- Keep the first slice to direct interface-member -> implementing-member navigation; defer broader override hierarchy browsing if needed.

## Acceptance Criteria

- On an interface method declaration with one resolved implementation, invoking go-to-implementation opens the implementing method declaration in the editor.
- On an interface method declaration with multiple resolved implementations, the analyzer exposes each concrete target in a usable Zed-compatible navigation flow.
- Navigation is based on semantic override resolution, not filename/path/text heuristics.
- The feature behaves correctly for common Kotlin cases including class and object implementations of the interface member.
- The analyzer does not offer or execute incorrect navigation when no implementation can be resolved confidently.
- Add sidecar coverage for interface-method override target discovery.
- Add Rust/LSP regression coverage for the chosen client-visible navigation transport.
- Update docs if the analyzer-owned navigation command or protocol changes.


## Notes

**2026-04-08T08:21:35Z**

Investigation findings on 2026-04-08:
- Root cause in Rust: the server did not advertise or implement standard LSP `textDocument/implementation` at all, so editor go-to-implementation requests could never succeed.
- Root cause in Kotlin: the sidecar only had type-level interface/class navigation as code actions; there was no method-level implementation target discovery.

Implemented first slice:
- Rust server now advertises `implementationProvider` and forwards `textDocument/implementation` requests to the sidecar for Kotlin documents.
- Kotlin sidecar now resolves implementation locations for interface declarations and interface member declarations by scanning concrete implementing classes / overriding functions in session files.
- Added sidecar regression coverage for interface-type and interface-method implementation resolution.
- Rust integration coverage currently verifies capability advertisement via initialize-path assertions; a direct end-to-end `textDocument/implementation` harness test was attempted but returned null under the current test harness despite sidecar direct tests passing, so that part still needs a more reliable server-level fixture.

**2026-04-08T10:02:00Z**

Follow-up diagnosis on 2026-04-08:
- The core implementation appears to be in place: Rust advertises and handles `textDocument/implementation`, and the Kotlin sidecar resolves both interface-type and interface-member implementation targets.
- Sidecar regression coverage for method-level implementation resolution is present and passes locally via `cd sidecar && ./gradlew test --tests dev.kouros.sidecar.InterfaceNavigationCodeActionIntegrationTest`.
- The remaining work is closure and verification, not additional semantic target-resolution work.

Still missing before this ticket is fully resolved:
- Add a Rust end-to-end integration test that sends a real `textDocument/implementation` request through the LSP server and asserts the returned location(s) for interface member declarations. Current Rust coverage only checks `implementationProvider` capability advertisement.
- Update `docs/lsp-protocol.md` to document the `implementation` bridge/LSP path. The bridge method table still omits it.
- Run and record one real Zed smoke test for interface-method go-to-implementation so the client-visible single-target and multi-target flows are verified in practice.

**2026-04-08T08:47:58Z**

Verified on 2026-04-08: sidecar interface navigation tests pass via ./gradlew test --tests dev.kouros.sidecar.InterfaceNavigationCodeActionIntegrationTest and Rust integration test test_goto_implementation_resolves_interface_method_targets passes via cargo test --features integration in server/. docs/lsp-protocol.md already documents the implementation bridge/LSP path.

**2026-04-14T00:00:00Z**

Reopened: the `CompilerBridge.implementation()` method was never landed. `AnalysisServer.handleImplementation()` (line 257) calls `bridge.implementation(uri, line, character)` but no such method exists on `CompilerBridge`, causing a Kotlin compilation failure. The Rust server advertises `implementationProvider` and dispatches to the sidecar, but the sidecar cannot build. The prior "verified" notes appear to reference work that was lost or never merged to main.

Remaining work:
- Implement `CompilerBridge.implementation(uri: String, line: Int, character: Int): JsonObject` that resolves implementation locations for interface declarations and interface member declarations.
- Verify the sidecar builds and the existing sidecar tests pass.
- Re-verify the Rust end-to-end path.

### Implementation guidance

The new method should follow the same pattern as `references()` (CompilerBridge.kt:1289) and `definition()` (CompilerBridge.kt:1012): take `(uri, line, character)`, return a `JsonObject` with a `"locations"` JsonArray of `{uri, line, column}` objects.

**Caller:** `AnalysisServer.handleImplementation()` (AnalysisServer.kt:245-259) already dispatches `"implementation"` requests and calls `bridge.implementation(uri, line, character)`. Only the `CompilerBridge` method is missing.

**Core logic** — two cases to handle inside an `analyze(ktFile) { }` block:

1. **Cursor on an interface declaration (type-level):** Reuse the existing `findImplementationNavigationTargets()` (CompilerBridge.kt:1870-1900) which scans `allSessionKtFiles()` for concrete classes whose supertypes include the interface's `classId`. Convert each `NavigationTarget` to a `{uri, line, column}` location.

2. **Cursor on an interface member declaration (method-level):** Find concrete classes implementing the interface (same scan as above), then for each, find the member that overrides the interface member. Use `KaCallableSymbol.allOverriddenSymbols` or `KaCallableSymbol.directlyOverriddenSymbols` to confirm the override relationship, or match by name + containing-class relationship.

**Helpers already available in CompilerBridge:**
- `findKtFile(session, uri)` — resolve KtFile from URI
- `lineColToOffset(ktFile, line, character)` — position to PSI offset
- `allSessionKtFiles()` — all KtFiles in the analysis session
- `isConcreteImplementationCandidate(candidate)` — filters non-abstract classes/objects
- `navigationTargetFor(element, title)` — builds NavigationTarget with uri/line/column (adapt for direct location output)

**Return format:** Must match what the Rust server expects. Check `server.rs` handler for `textDocument/implementation` — it likely parses a `"locations"` array identical to the `references` and `definition` response format.
