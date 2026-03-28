# Sidecar Reliability & LSP Feature Availability

Findings from a thorough investigation into why LSP features (hover, code actions, completions) are frequently unavailable or unresponsive.

## Root Causes (by impact)

### 1. Single-threaded sidecar blocks everything during `analyzeAll`

**Severity**: Critical
**Files**: `sidecar/src/main/kotlin/dev/kouros/sidecar/AnalysisServer.kt`

The sidecar runs a sequential event loop (`while (running) { readRequest(); handleRequest() }`). While `analyzeAll` runs (can take minutes for large projects), no other request can be processed — hover, code actions, completions all queue up in the stdin buffer. This is the primary reason features only work "after the analysis completes."

**Fix options**:
- A) Run `analyzeAll` on a background thread, allowing the event loop to continue serving other requests. Requires thread-safe access to `CompilerBridge` (the Analysis API session is single-threaded, so analysis calls would need a mutex or queue).
- B) Split `analyzeAll` into per-file `analyzeFile` calls driven from the Rust side, interleaved with other requests.
- C) Stream diagnostics: sidecar sends partial results as JSON-RPC notifications while analyzing, so the event loop isn't blocked.

### 2. Sidecar crash-loops during code actions

**Severity**: Critical
**Files**: `sidecar/src/main/kotlin/dev/kouros/sidecar/CompilerBridge.kt` (line ~1429)

`codeActions()` calls `collectDiagnostics(EXTENDED_AND_COMMON_CHECKERS)` on every request. This crashes the JVM on certain files (especially Spring test files with `@WebMvcTest` and complex annotations). Observed: 33 failures vs 7 successes. After crashing, the sidecar enters a restart loop where it exits within ~1 second of restarting.

**Fix**:
- Cache diagnostics from `analyzeAll()` results and look them up by file+line in `codeActions()` instead of re-running the full diagnostic checker. This eliminates the redundant expensive call and avoids the crash trigger.
- Wrap the `collectDiagnostics` call in a try-catch that doesn't bring down the process.

### 3. Request cancellation race (code actions)

**Severity**: High
**Files**: `server/src/server.rs` (code_action handler ~line 1464)

Code actions take 550-920ms to produce. Zed sends `textDocument/codeAction` on every cursor position change. When the user moves the cursor during this window, Zed sends `$/cancelRequest` and tower-lsp discards the pending response. The sidecar still processes the request (it doesn't support cancellation), wasting work.

**Fix options**:
- Debounce code action requests on the Rust side (wait ~200ms before forwarding to sidecar)
- Cache recent code action responses and serve stale results while a new request is in-flight
- Both fixes complement each other

### 4. Full session rebuild on every edit

**Severity**: High
**Files**: `sidecar/src/main/kotlin/dev/kouros/sidecar/CompilerBridge.kt` (`ensureSessionCurrent()` ~line 376, `updateFile()` ~line 314)

Every `didChange` event calls `updateFile()` which sets `sessionDirty = true`. The next request (hover, completion, etc.) calls `ensureSessionCurrent()` which triggers a full `initialize()` rebuild — seconds of blocking on the single thread. This means every keystroke that changes content causes a multi-second stall on the next LSP request.

**Fix options**:
- Debounce session rebuilds (don't rebuild until N ms of inactivity)
- Use the existing session with LightVirtualFile overlay for immediate responses, rebuild in background
- Incremental session updates instead of full rebuild (requires Analysis API support)

### 5. `uriToPath()` doesn't URL-decode

**Severity**: Medium
**Files**: `sidecar/src/main/kotlin/dev/kouros/sidecar/CompilerBridge.kt` (`uriToPath()` ~line 4929)

`uriToPath()` does a naive `removePrefix("file://")` without URL-decoding. Paths with spaces (`%20`), non-ASCII characters, or other encoded characters will not match in `findKtFile()`. Example: `file:///home/user/my%20project/Main.kt` produces `/home/user/my%20project/Main.kt` but `shadowPathMapping` contains `/home/user/my project/Main.kt`.

**Fix**: Use `java.net.URI` to properly parse and decode the URI:
```kotlin
fun uriToPath(uri: String): String {
    return java.net.URI(uri).path
}
```

### 6. Progress reporting is decorative

**Severity**: Low
**Files**: `server/src/server.rs` (lines 796-893)

Progress shows 0% during the entire blocking `analyzeAll` call, then jumps to done. The progress loop at line 879 iterates through already-computed results (near-instant), not in-progress analysis.

**Fix**: Requires streaming progress from the sidecar (see issue #1 fix options). Without that, progress reporting is meaningless.

### 7. Silent failures return empty results instead of errors

**Severity**: Low (UX/debuggability)
**Files**: `sidecar/src/main/kotlin/dev/kouros/sidecar/CompilerBridge.kt` (hover, codeActions, etc.)

When `session` is null, `findKtFile()` returns null, `lineColToOffset()` returns null, or analysis fails, the sidecar returns an empty `JsonObject()` — no error, no reason field. The Rust side can't distinguish "nothing to hover on" from "resolution broke." Makes debugging difficult.

**Fix**: Add a `"reason"` or `"error"` field to responses when results are empty due to failures, so the Rust side can log meaningful diagnostics.

## Suggested Priority Order

1. **Cache diagnostics for code actions** (#2) — stops crash-loops, quick win
2. **Debounce code action requests** (#3) — prevents wasted work from cancellation
3. **Fix `uriToPath()` URL decoding** (#5) — one-line fix, unblocks users with spaces in paths
4. **Async `analyzeAll`** (#1) — biggest architectural change, biggest user impact
5. **Debounce session rebuilds** (#4) — prevents per-keystroke stalls
6. **Silent failure diagnostics** (#7) — improves debuggability
7. **Streaming progress** (#6) — nice-to-have UX improvement
