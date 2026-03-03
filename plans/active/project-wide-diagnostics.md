# Project-Wide Diagnostics

## Current State

kotlin-analyzer only publishes diagnostics for files open in editor tabs. The sidecar already loads all source files into the Analysis API session (e.g. "session has 125 file(s)"), but `collectDiagnostics` is only called when `analyze` is requested for a specific file via `did_open` or `did_change`.

This means errors in files you haven't opened are invisible until you open them.

## Goal

After sidecar initialization, analyze all project source files in the background and publish diagnostics progressively. Open files take priority over background analysis.

## Design

### Approach: Background crawl after startup

After the sidecar reaches `Ready` and the open-document replay finishes, spawn a background task that walks source roots and analyzes every `.kt` file that hasn't already been analyzed.

### Sidecar changes

**New RPC method: `analyzeAll`**

Returns diagnostics for all files known to the session in a single call. This avoids the overhead of one round-trip per file.

```json
// Request
{ "method": "analyzeAll" }

// Response
{
  "files": [
    { "uri": "file:///src/main/kotlin/Foo.kt", "diagnostics": [...] },
    { "uri": "file:///src/main/kotlin/Bar.kt", "diagnostics": [] },
    ...
  ]
}
```

Implementation in `CompilerBridge.kt`:
- Iterate `session.modulesWithFiles` to get all `KtFile` objects
- For each `.kt` file (skip `.kts` build scripts):
  - If the file has an entry in `virtualFiles` (open in editor), use that content
  - Otherwise use the session's on-disk content
  - Call `collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)`
- Return all results in one response

The sidecar already has all files parsed. The cost is calling `collectDiagnostics` on each, which is the expensive part (30-900ms per file in observed logs). For 125 files this could take 30-60 seconds total.

### Server changes (Rust LSP)

**1. Background analysis task**

In the `initialized` handler, after the replay loop completes:

```rust
// After replay finishes, start background analysis
tokio::spawn(async move {
    // Small delay to let open-file diagnostics settle
    tokio::time::sleep(Duration::from_secs(2)).await;

    let bridge = bridge_holder.lock().await;
    if let Some(bridge) = bridge.as_ref() {
        match bridge.request("analyzeAll", None).await {
            Ok(result) => {
                // Parse and publish diagnostics for each file
                // Skip files already analyzed during replay
            }
            Err(e) => tracing::warn!("background analysis failed: {}", e),
        }
    }
});
```

**2. Diagnostic caching for non-open files**

The `DocumentStore.diagnostics` map already supports any URI. Background analysis results are cached there. When a file is later opened via `did_open`, the cached diagnostics are republished instantly (this path already exists in `did_open`, lines 778-787).

**3. Progress reporting**

Show background analysis progress to the user:
- "Analyzing project (0/125 files)..."
- Update periodically as files complete
- "Project analysis complete — 4 errors, 2 warnings"

### File filtering

Skip these files during background analysis:
- `build.gradle.kts`, `settings.gradle.kts` (per gradle-kts-support plan)
- Files under `buildSrc/` with `.kts` extension
- Files under `.gradle/`, `build/`, or other output directories
- Non-Kotlin files that happen to be in source roots

### Priority and cancellation

- If the user opens or edits a file while background analysis is running, the on-demand analysis for that file takes priority
- If the sidecar crashes or transitions to `Degraded`, cancel background analysis
- If the project model changes (build file edit), restart background analysis

## Alternatives Considered

### Streaming per-file analysis

Instead of `analyzeAll`, keep the existing `analyze(uri)` method and call it in a loop from the Rust server, iterating source files discovered from `ProjectModel.source_roots`.

Pros: No sidecar changes needed.
Cons: One RPC round-trip per file (125 round-trips). More coordination in the Rust server to discover files. The sidecar already knows which files exist in the session, so it's better positioned to iterate them.

### LSP pull-model diagnostics (workspace/diagnostic)

LSP 3.17 introduced `workspace/diagnostic` as a pull-model alternative to push-based `publishDiagnostics`. The client requests diagnostics rather than the server pushing them.

Pros: Standard LSP protocol. Client controls timing.
Cons: Requires client support (Zed does support it, but push-model is simpler and more universal). Would need both pull and push implementations since individual file updates still need push.

Not worth the complexity for now. Can be added later if needed.

## Dependencies

- **gradle-kts-support.md Phase 1**: Must skip `.kts` build scripts before running project-wide analysis, otherwise hundreds of false positives.
- **Test classpath** (separate concern): Background analysis will surface false positives from test files unless test dependencies are added to the classpath. Consider either:
  - Including `testCompileClasspath` and test source roots in project resolution
  - Or skipping test source roots during background analysis until test classpath support is added

## Implementation Order

1. ~~Implement `.kts` build script filtering (gradle-kts-support Phase 1)~~ — Done
2. ~~Add `analyzeAll` RPC method to sidecar~~ — Done
3. ~~Add background analysis task to `initialized` handler in server~~ — Done
4. ~~Add progress reporting~~ — Done
5. (Later) Add test classpath support to project resolution so test files don't produce false positives

## Implementation Notes

### What was implemented

**Sidecar (`CompilerBridge.kt` + `AnalysisServer.kt`):**
- New `analyzeAll()` method iterates `session.modulesWithFiles`, skips `.kts` and build output directories, calls `collectDiagnostics` on each `.kt` file, returns JSON with per-file diagnostics plus summary counts (`totalFiles`, `totalErrors`, `totalWarnings`).
- New `analyzeAll` RPC handler wired in `AnalysisServer`.

**Rust LSP (`server.rs`):**
- `.kts` filtering added to `analyze_document()` — early return for `.gradle.kts`, `settings.gradle.kts`, and `.kts` files in `buildSrc/` or `gradle/`.
- Background analysis task spawned in `initialized()` after the replay loop: waits 2s, creates progress token, calls `analyzeAll` with 5-minute timeout, publishes diagnostics for non-open files, caches in DocumentStore, reports progress every 10 files.

**Bridge (`bridge.rs`):**
- New `request_with_timeout()` method for long-running RPC calls (used by `analyzeAll` with 300s timeout).
