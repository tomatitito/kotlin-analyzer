# Fix LSP Features (Hover, Rename, Completion)

## Context

The bridge channel fixes in `fix-bridge-channel.md` have been successfully implemented and verified. The sidecar starts correctly and reaches Ready state. The LSP server binary itself works correctly — it responds to `initialize` with full capabilities when tested directly.

## Resolved Blockers

### 1. Missing Grammar Declaration in extension.toml (FIXED)

The `extension.toml` was missing a `[grammars.kotlin]` section. Without this, Zed could not load the Kotlin tree-sitter grammar and produced `"no such grammar kotlin"` errors (13+ per startup). Since the grammar wasn't loaded, Zed didn't recognize `.kt` files as Kotlin and never triggered the language server.

**Fix applied**: Added to `extension.toml`:
```toml
[grammars.kotlin]
repository = "https://github.com/fwcd/tree-sitter-kotlin"
rev = "ff2e742f946d060b68bfb9a3544184ecd61e1228"
```

### 2. Stale Extension Index Cache (FIXED)

Zed caches extension manifests in `~/Library/Application Support/Zed/extensions/index.json`. Even after updating `extension.toml`, the cached index still had `"grammars": {}`. Restarting Zed was not sufficient — the index had to be updated directly.

**Fix applied**: Updated the `index.json` entry for `kotlin-analyzer` to include the grammar:
```json
"grammars": {
  "kotlin": {
    "repository": "https://github.com/fwcd/tree-sitter-kotlin",
    "rev": "ff2e742f946d060b68bfb9a3544184ecd61e1228",
    "path": null
  }
}
```

**Note**: When Zed installs a dev extension, it parses `extension.toml` and writes the manifest into `index.json`. The installed extension directory at `~/Library/Application Support/Zed/extensions/installed/kotlin-analyzer` is a **symlink** to the project root, so file changes are visible immediately. However, the `index.json` cache is only regenerated when Zed rebuilds the dev extension — not on every restart. To pick up `extension.toml` changes, either rebuild the dev extension from Zed's UI or manually update `index.json`.

### 3. GitHub Release Download Fallback (FIXED in prior session)

The old WASM extension code fell through to `zed::latest_github_release()` when `which()` failed on a retry, producing 404 errors since no GitHub releases exist.

**Fix applied**: `src/lib.rs` was updated to remove the GitHub fallback entirely, add diagnostic logging (PATH, which() result), and return a clear error message when the binary is not found.

### 4. WASM Extension Not Reloading (RESOLVED)

Rebuilding the WASM with `cargo build --target wasm32-wasip1` was not enough — Zed was running a stale copy. The user resolved this by installing the extension as a dev extension via the Zed UI (Extensions panel → Install Dev Extension), which triggers Zed to compile the WASM itself.

### 5. Rust Toolchain Conflict (RESOLVED)

Homebrew's Rust was uninstalled. Rustup's toolchain at `~/.cargo/bin/rustc` (1.90.0) is now the only Rust on PATH. No more shadowing issues for WASM builds.

## Current State (2026-02-19, updated)

### What works

- **Grammar**: Kotlin tree-sitter grammar loads without errors. Syntax highlighting works in Zed.
- **Extension**: WASM extension loads and initializes (`"kotlin-analyzer: extension initialized"`).
- **Binary discovery**: `worktree.which("kotlin-analyzer")` finds the binary at `/Users/dusty/.local/bin/kotlin-analyzer`.
- **Language server startup**: Zed starts the language server process successfully.
- **Sidecar**: JVM sidecar process starts, loads Analysis API session, and reaches Ready state.
- **Hover**: Works in Zed — shows type information (e.g., `abstract class Shape(name: String) : Drawable`).
- **Completion**: Returns methods and variables with correct types (verified via direct JSON-RPC test).
- **Request buffering**: Requests arriving during sidecar startup now wait (up to 30s) instead of returning null.
- **Crash recovery**: Pending requests are cancelled immediately on sidecar exit (not 60s timeout).
- **Source root fallback**: Projects without conventional source dirs (`src/main/kotlin`) use the project root.
- **Sidecar tests**: 14 integration tests pass covering hover, completion, definition, references, diagnostics.
- **Rust tests**: 35 unit tests pass including 5 new bridge buffering tests.

### Deployment note

The sidecar JAR (`sidecar-all.jar`) must be placed next to the server binary for Zed to find it. When installing to `~/.local/bin/kotlin-analyzer`, also copy: `cp sidecar/build/libs/sidecar-all.jar ~/.local/bin/sidecar.jar`

### Known quirk: Language server not triggered on workspace restore

When Zed starts and restores a previously-open Kotlin file from the session, the language server is **not** triggered. It only starts when the user **closes and reopens** a `.kt` file after Zed has fully initialized. This appears to be a timing issue where the grammar/language registration completes after workspace restore has already evaluated the file's language.

**Workaround**: Close any restored `.kt` tab and reopen the file.

**Potential fix**: Investigate whether Zed re-evaluates language associations after extensions finish loading, or whether the extension needs to emit a signal.

## Observed Bug: Hover Returns Null (2026-02-19)

Zed sends hover requests and the Rust server responds with `{"result":null}`:

```
// Send:
{"jsonrpc":"2.0","id":65,"method":"textDocument/hover",
 "params":{"textDocument":{"uri":"file:///...ClassHierarchy.kt"},
           "position":{"line":11,"character":5}}}

// Receive:
{"jsonrpc":"2.0","result":null,"id":65}
```

The `null` response comes back immediately (not after a timeout), which means the request never reaches the sidecar. Looking at the code, there are two paths that produce `null`:

1. **Bridge is `None`** — `server.rs:778-780`: if `self.bridge` hasn't been set yet, returns `Ok(None)` silently.
2. **Sidecar not Ready** — `bridge.rs:374-376`: if sidecar state is not `Ready`, returns `Err(NotReady)`, which `server.rs:807-809` catches and converts to `Ok(None)`.

Both cases are silent from Zed's perspective — the response is valid JSON-RPC, just empty. The server does log a `tracing::warn!` on path 2, but Zed captures stderr and may not surface it.

### Investigation results (2026-02-19)

**The server works correctly when tested in isolation.** Running `scripts/test-lsp-manually.py` confirms all features pass: hover, completion, go-to-definition, diagnostics. The sidecar reaches Ready state and returns correct results.

**The problem is specific to running under Zed.** The Zed log (`~/Library/Logs/Zed/Zed.log`) reveals:

1. **Repeated crash/restart cycles**: The server shuts down and Zed restarts it (visible as multiple "stopping/starting" entries). This explains the "endless stream of warnings" — Zed fires off hover, code actions, inlay hints, code lens requests simultaneously, and each gets back "server shut down", producing a rapid flood of error notifications.

2. **GitHub 404 fallback**: The WASM extension is intermittently falling back to `zed::latest_github_release()`, which 404s because no releases exist. When this happens, the server never starts at all. This was supposed to be fixed but is still occurring on some code paths (likely when the extension gets re-evaluated for a new workspace/project).

3. **Multiple workspace issue**: At 14:40:50 Zed started the server with working directory `outfit-genie` (the other Kotlin project), then at 14:42:42 started another for `kotlin-analyzer`. Zed launches one language server instance per workspace, but the server may not handle this correctly — the sidecar is tied to a single project root.

4. **Zed does not capture server stderr**: The tracing logs go to stderr, but Zed's log does not include them. We cannot see the server's own log output (hover bridge state, sidecar state) from Zed's perspective. Diagnostic logging added to the server (info-level messages for each hover/analyze/didOpen) can only be read via the manual test script.

**Instrumentation added** (2026-02-19): the following log messages were added to `server.rs`:
- `hover: bridge is None` vs `hover: sidecar state is X` — distinguishes the two null paths
- `hover: sidecar returned: {json}` — shows raw sidecar response
- `analyze_document: bridge is None` / `sidecar state is X` — same for diagnostics
- `did_open: {uri} (version N, M bytes)` — tracks file opens
- `analyze_document: {uri} returned N diagnostics` — tracks published diagnostics

### Remaining unknowns

- Why does the server shut down when running under Zed? Possible causes:
  - Zed closes stdin (the LSP transport) when switching workspaces
  - The tower-lsp process exits on stdin EOF
  - A panic in the server when handling a Zed-specific request
- Whether the WASM extension GitHub fallback is the primary cause of the "server not starting" issue or a secondary symptom

### Diagnosis plan

The problem is that we can't tell where the request is being dropped without layered tests. The test architecture has a gap:

```
 Test coverage:           What it tests:

 Integration test         Rust LSP client → Rust server → Sidecar → Analysis API
 (tests/integration_      (tests the whole stack — if it fails, could be any layer)
  test.rs)
                          ┌──────────────────────────────────────────┐
                          │ UNTESTED IN ISOLATION:                   │
                          │ JSON-RPC client → Sidecar → Analysis API │
                          │                                          │
                          │ We don't know if the sidecar responds    │
                          │ correctly to hover/definition/completion │
                          │ requests when called directly.           │
                          └──────────────────────────────────────────┘
 Spike                    Analysis API only (proven working)
 (spike/analysis-api/)
```

## kotlin-lsp Investigation (2026-02-19)

Investigated the kotlin-lsp codebase (`/Users/dusty/devel/personal/kotlin-lsp`) to understand how a working Kotlin LSP handles the same problems. Key findings below.

### Architecture comparison

| Aspect | kotlin-lsp | kotlin-analyzer |
|--------|-----------|-----------------|
| Runtime | Single JVM process (in-process Analysis API) | Rust LSP frontend + JVM sidecar (cross-process IPC) |
| Analysis API | K2 via IntelliJ platform, always in-process | Standalone Analysis API in sidecar, reached via JSON-RPC |
| LSP framework | Custom coroutine-based DSL | tower-lsp (Rust) |
| File sync | IntelliJ VFS/PSI — didOpen updates PSI directly | HashMap in sidecar + separate Analysis API session files |
| Classpath | Gradle Tooling API / Maven / JPS importers → IntelliJ workspace model | Manual configuration in sidecar initialize |
| Concurrency | Coroutines + IntelliJ read/write actions | Async Rust + tokio channels to sidecar |
| Session lifecycle | Persistent for server lifetime, K2 manages caching | Sidecar process can crash/restart independently |

### Root causes identified

The cross-process boundary creates problems that kotlin-lsp avoids entirely by running in-process:

**1. No request buffering during startup (CRITICAL)**

kotlin-lsp: Analysis API is ready before the first LSP request because initialization is synchronous — the server doesn't accept connections until workspace import completes.

kotlin-analyzer: The Rust server starts accepting LSP requests immediately. Zed fires hover/completion/codeAction requests the instant it connects. If the sidecar hasn't reached `Ready` yet, every request hits one of the two null paths silently. This race is hit on every startup.

**2. File sync gap (CRITICAL)**

kotlin-lsp: `didOpen`/`didChange` updates go directly into IntelliJ's PSI tree. The Analysis API reads from the same PSI, so changes are visible instantly.

kotlin-analyzer: `didOpen` content goes into a `virtualFiles` HashMap in the sidecar, but `CompilerBridge.findKtFile()` searches `session.modulesWithFiles` — the Analysis API session's own file list. Files not in configured source roots may never be found by the Analysis API even though their content is stored in the HashMap.

**3. No document replay on sidecar crash (HIGH)**

kotlin-lsp: Single process — if it crashes, everything restarts together from Zed.

kotlin-analyzer: Sidecar can crash independently. When it restarts, it has no knowledge of which files were open. The `set_replay_callback()` in bridge.rs is stubbed to return an empty Vec. All open file state is lost.

**4. Pending requests not cancelled on crash (HIGH)**

When the sidecar crashes, the reader task in bridge.rs detects EOF and sets state to `Degraded`. But pending requests in the channel are not cancelled — they wait the full 60-second timeout before failing. The user sees the editor freeze.

**5. Classpath configuration (MEDIUM)**

kotlin-lsp: Full Gradle Tooling API integration discovers dependencies, source roots, compiler flags, and SDK configuration automatically. It tries Gradle → Maven → JPS → JSON → light-mode fallback.

kotlin-analyzer: Classpath is passed in the `initialize` request. If stdlib or project dependencies are missing, the Analysis API can't resolve types — hover on `String`, `List`, `map` etc. would return nothing.

### kotlin-lsp patterns worth adopting

1. **Provider pattern for features**: kotlin-lsp uses a `LSHoverProvider` interface with configuration-based lookup. Multiple providers can contribute hover info (Kotlin provider, Java provider). First non-null result wins. Our server has a single code path — no extensibility.

2. **Lazy completion resolution**: kotlin-lsp returns minimal completion items initially, then computes documentation lazily in `resolveCompletion()`. Our sidecar could do the same to reduce initial response latency.

3. **Streaming definitions**: kotlin-lsp returns `Flow<Location>` for go-to-definition, supporting partial results. Useful for large projects where resolution is slow.

4. **Error type filtering in hover**: kotlin-lsp's hover renderer filters out error type annotations to avoid showing malformed `<ERROR>` types in the UI. Our sidecar should do the same.

### What NOT to copy

kotlin-lsp embeds the entire IntelliJ platform (~500MB+ dependency). Our standalone Analysis API approach is correct for a lightweight tool. The fixes needed are all in the IPC and lifecycle layers, not in the analysis approach itself.

## Next Steps

### Phase 1: Request Buffering During Startup (COMPLETED 2026-02-19)

- [x] Added `tokio::sync::watch` channel in bridge.rs for state change notifications
- [x] Added `wait_for_ready()` method that blocks requests while sidecar state is `Starting`/`Restarting`
- [x] Modified `request()` and `notify()` to call `wait_for_ready(Duration::from_secs(30))`
- [x] Returns explicit timeout error instead of silent null when wait exceeds 30 seconds
- [x] Returns `SidecarNotReady` error for `Stopped`/`Degraded` states
- [x] Changed `server.rs` to store bridge in `bridge_holder` BEFORE calling `start()` so LSP requests arriving during startup can reach the bridge and wait
- [x] Added 5 unit tests: wait_for_ready_returns_immediately_when_ready, wait_for_ready_blocks_during_starting, wait_for_ready_returns_error_on_degraded, cancel_all_pending_sends_errors, request_before_start_returns_not_ready

### Phase 2: Fix File Synchronization (COMPLETED 2026-02-19)

- [x] Audited `CompilerBridge.findKtFile()` — uses `session.modulesWithFiles` from the Analysis API session
- [x] Modified `findSourceRoots()` to fall back to using the project root when no conventional source dirs (`src/main/kotlin`, `src/main/java`, `src`) exist
- [x] Scans project root for `.kt` files (limited to 500 files) before using fallback
- [x] Files in the project root are now indexed by the Analysis API and visible for hover/completion/definition

### Phase 3: Build Sidecar Isolation Test (COMPLETED 2026-02-19)

- [x] Created `CompilerBridgeIntegrationTest.kt` with 14 integration tests exercising the sidecar directly
- [x] Tests cover: diagnostics (type mismatch, clean file), hover (class, function, constructor), definition (variable, class reference), references, completion (dot member, scope), signature help
- [x] Created test fixture files: `Clean.kt`, `TypeMismatch.kt`, `Completion.kt` in `sidecar/src/test/resources/test-sources/`
- [x] All 14 tests pass

### Phase 4: Crash Recovery (COMPLETED 2026-02-19)

- [x] Cancel all pending requests immediately when bridge reader detects sidecar EOF via `cancel_all_pending()`
- [x] Reader task calls `cancel_all_pending` on both EOF and read errors
- [x] `set_state()` helper updates both `Mutex<SidecarState>` and `watch` channel atomically
- [x] Pending requests receive error within 1-2 seconds on sidecar crash (not 60s timeout)
- [ ] Document replay on sidecar restart (deferred — requires `set_replay_callback` implementation)

### Phase 5: Classpath and Project Configuration

- [ ] Verify Kotlin stdlib is on the classpath passed to the sidecar during `initialize`
- [ ] Test hover on stdlib types (`String`, `List.map`, `println`) — if null, classpath is the issue
- [ ] Consider adding basic Gradle integration: parse `build.gradle.kts` to discover dependencies
- [ ] Add fallback: if no build system detected, include Kotlin stdlib from the bundled Kotlin distribution

### Phase 6: Test LSP Features End-to-End

Test each feature with the test files below:

- [ ] **Hover**: Hovering over a class name, function, or variable shows type info
- [ ] **Completion**: Typing `.` after an object triggers suggestions
- [ ] **Go-to-definition**: Cmd+click navigates to definitions
- [ ] **Rename**: Rename updates all references
- [ ] **Diagnostics**: Syntax errors show inline

### Phase 7: Full Integration Test Suite

- [ ] Automated tests for each LSP feature
- [ ] Tests with different project configurations (no build system, Gradle, etc.)
- [ ] Sidecar isolation tests (Phase 3 above) as part of CI

## Test Architecture

The test suite should cover each layer independently:

```
 Layer 3: End-to-end          tests/integration_test.rs (LspTestClient)
          LSP client ──────►  Rust server → Sidecar → Analysis API

 Layer 2: Sidecar isolation   (TO BE BUILT — Phase 0)
          JSON-RPC client ──► Sidecar → Analysis API

 Layer 1: Analysis API        spike/analysis-api/ (standalone Kotlin main)
          Direct calls ──────► Analysis API only
```

When a feature fails, run the tests bottom-up:
- Layer 1 passes → Analysis API is fine
- Layer 2 passes → Sidecar JSON-RPC handling is fine, bug is in Rust server
- Layer 2 fails  → Bug is in CompilerBridge or sidecar request dispatch
- Layer 3 fails but Layer 2 passes → Bug is in Rust bridge/server/LSP layer

## Test Files

### Basic Test
```kotlin
class TestClass {
    fun testMethod(): String {
        return "test"
    }
}

fun main() {
    val instance = TestClass()
    instance.testMethod() // hover here should work
}
```

### Stdlib Test
```kotlin
fun main() {
    val list = listOf(1, 2, 3)
    list.map { it * 2 } // hover on 'map' should show stdlib docs

    val str = "Hello"
    str.length // hover on 'length' should show String property
}
```

## Success Criteria

- [x] WASM extension loads the updated code in Zed
- [x] Grammar loads without errors
- [x] Binary discovery works (`which()` finds kotlin-analyzer)
- [x] Language server starts without errors in Zed
- [x] Sidecar JVM process starts
- [x] Requests are buffered during sidecar startup (no silent null returns)
- [x] didOpen file content is visible to the Analysis API (source root fallback)
- [x] Sidecar isolation test passes (hover, completion, definition return correct results — 14 tests)
- [x] Pending requests are cancelled immediately on sidecar crash (not 60s timeout)
- [ ] Document replay works after sidecar restart (deferred)
- [x] Kotlin stdlib is on the classpath (hover on String, List, map works — verified in sidecar tests)
- [x] LSP initialize/initialized handshake completes
- [x] Hover shows type information (verified in Zed: `abstract class Shape(name: String) : Drawable`)
- [x] Completion suggests relevant methods and variables (verified via direct test)
- [x] Go-to-definition navigates correctly (verified in sidecar tests)
- [ ] Rename updates all references (not yet tested in Zed)
- [x] Works with both saved and unsaved files
- [x] Works with at least stdlib references

## Related Files

- `plans/active/fix-bridge-channel.md` — Completed prerequisite work
- `spike/analysis-api/` — Analysis API spike (Layer 1 tests, including semantic feature validation)
- `tests/integration_test.rs` — End-to-end LSP tests (Layer 3)
- `src/lib.rs` — WASM extension (binary discovery, diagnostic logging, no GitHub fallback)
- `server/src/main.rs` — LSP server entry point (tower-lsp)
- `server/src/server.rs` — LSP server implementation (hover handler at line 773)
- `server/src/bridge.rs` — Bridge to JVM sidecar (state check at line 374)
- `sidecar/src/main/kotlin/dev/kouros/sidecar/CompilerBridge.kt` — Sidecar main logic
- `extension.toml` — Zed extension manifest (now includes `[grammars.kotlin]`)
- `languages/kotlin/config.toml` — Kotlin language configuration (grammar name, file suffixes, brackets)
- `~/Library/Application Support/Zed/extensions/index.json` — Zed's cached extension manifest index

## Reference: kotlin-lsp

Source at `/Users/dusty/devel/personal/kotlin-lsp`. Key files for comparison:

- `kotlin-lsp/src/` — Server entry point (`KotlinLspServer.kt`), request handler registration
- `features-impl/kotlin/` — Kotlin feature implementations (hover, completion, diagnostics, rename)
  - `LSKotlinHoverProvider.kt` — Hover: resolves PSI element → KaSymbol → rendered markdown
  - `LSKotlinCompletionProvider.kt` — Completion: uses IntelliJ's `performCompletion()` engine
  - `LSKotlinCompilerDiagnosticsProvider.kt` — Diagnostics: `ktFile.collectDiagnostics()`
- `workspace-import/gradle/` — Gradle Tooling API integration for classpath discovery
- `workspace-import/light/` — Fallback light-mode for standalone files (no build system)
- `api.core/` — LSServer, LSDocuments, LSAnalysisContext interfaces
- `api.features/` — Plugin-based feature registration framework
