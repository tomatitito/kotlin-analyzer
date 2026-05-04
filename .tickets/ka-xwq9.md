---
id: ka-xwq9
status: closed
deps: [ka-4dsl, ka-b4j7, ka-zbgz, ka-8vwe, ka-vu27, ka-lws3, ka-9oze, ka-d994]
links: []
created: 2026-04-14T11:53:08Z
type: task
priority: 2
assignee: Jens Kouros
parent: ka-ftb5
tags: [refactoring, sidecar]
---
# Finalize CompilerBridge facade

Slim CompilerBridge.kt to ~150-line facade delegating to BridgeContext extension functions.

## Final CompilerBridge Structure

After all feature extraction tickets and the shared navigation helper extraction are complete, CompilerBridge.kt becomes a thin facade. Every public method delegates to an extension function on `BridgeContext`. The companion object stays on CompilerBridge as test/API compatibility wrappers around `CompilerBridgeRuntime`.

```kotlin
package dev.kouros.sidecar

import com.google.gson.JsonObject

/**
 * Facade over the Kotlin Analysis API.
 * Delegates all feature logic to BridgeContext extension functions
 * defined in separate files.
 */
class CompilerBridge {
    internal val ctx = BridgeContext()

    // --- Lifecycle ---

    fun initialize(
        projectRoot: String,
        classpath: List<String>,
        compilerFlags: List<String>,
        jdkHome: String,
        sourceRoots: List<String>,
    ) = ctx.initialize(projectRoot, classpath, compilerFlags, jdkHome, sourceRoots)

    fun shutdown() = ctx.shutdown()

    // --- File management ---

    fun updateFile(uri: String, text: String) = ctx.updateFile(uri, text)
    fun removeFile(uri: String) = ctx.removeFile(uri)
    fun updatePebbleFile(uri: String, text: String) = ctx.updatePebbleFile(uri, text)
    fun removePebbleFile(uri: String) = ctx.removePebbleFile(uri)

    // --- Diagnostics ---

    fun analyze(uri: String, version: Int? = null): JsonObject = ctx.analyze(uri, version)
    fun analyzeAll(): JsonObject = ctx.analyzeAll()

    // --- Hover ---

    fun hover(uri: String, line: Int, character: Int): JsonObject = ctx.hover(uri, line, character)

    // --- Completion ---

    fun completion(uri: String, line: Int, character: Int, triggerCharacter: String? = null): JsonObject =
        ctx.completion(uri, line, character, triggerCharacter)

    // --- Navigation ---

    fun definition(uri: String, line: Int, character: Int): JsonObject = ctx.definition(uri, line, character)
    fun implementation(uri: String, line: Int, character: Int): JsonObject = ctx.implementation(uri, line, character)
    fun references(uri: String, line: Int, character: Int): JsonObject = ctx.references(uri, line, character)

    // --- Pebble ---

    fun pebbleDefinition(uri: String, line: Int, character: Int): JsonObject = ctx.pebbleDefinition(uri, line, character)
    fun pebbleReferences(uri: String, line: Int, character: Int): JsonObject = ctx.pebbleReferences(uri, line, character)

    // --- Refactoring ---

    fun signatureHelp(uri: String, line: Int, character: Int): JsonObject = ctx.signatureHelp(uri, line, character)
    fun rename(uri: String, line: Int, character: Int, newName: String): JsonObject = ctx.rename(uri, line, character, newName)
    fun codeActions(uri: String, line: Int, character: Int): JsonObject = ctx.codeActions(uri, line, character)

    // --- Symbols ---

    fun workspaceSymbols(query: String): JsonObject = ctx.workspaceSymbols(query)

    // --- Editor decorations ---

    fun inlayHints(uri: String, startLine: Int, endLine: Int): JsonObject = ctx.inlayHints(uri, startLine, endLine)
    fun codeLens(uri: String): JsonObject = ctx.codeLens(uri)
    fun semanticTokens(uri: String): JsonObject = ctx.semanticTokens(uri)

    // --- Hierarchies ---

    fun callHierarchyPrepare(uri: String, line: Int, character: Int): JsonObject = ctx.callHierarchyPrepare(uri, line, character)
    fun callHierarchyIncoming(uri: String, line: Int, character: Int, name: String): JsonObject = ctx.callHierarchyIncoming(uri, line, character, name)
    fun typeHierarchyPrepare(uri: String, line: Int, character: Int): JsonObject = ctx.typeHierarchyPrepare(uri, line, character)
    fun typeHierarchySupertypes(uri: String, line: Int, character: Int, name: String): JsonObject = ctx.typeHierarchySupertypes(uri, line, character, name)

    companion object {
        fun bundledKotlinVersion(): String = CompilerBridgeRuntime.bundledKotlinVersion()

        internal fun findStdlibJarsInRepository(repositoryRoot: Path, preferVersion: String? = null): List<Path> =
            CompilerBridgeRuntime.findStdlibJarsInRepository(repositoryRoot, preferVersion)

        fun mapCompilerFlag(flag: String): LanguageFeature? = CompilerBridgeRuntime.mapCompilerFlag(flag)
    }
}
```

## Complete Public API (26 methods)

| # | Method | Signature | Delegates to | Extraction ticket |
|---|--------|-----------|--------------|-------------------|
| 1 | `initialize` | `(projectRoot: String, classpath: List<String>, compilerFlags: List<String>, jdkHome: String, sourceRoots: List<String>)` | `ctx.initialize(...)` | ka-e6eo (BridgeContext) |
| 2 | `shutdown` | `()` | `ctx.shutdown()` | ka-e6eo (BridgeContext) |
| 3 | `updateFile` | `(uri: String, text: String)` | `ctx.updateFile(...)` | ka-e6eo (BridgeContext) |
| 4 | `removeFile` | `(uri: String)` | `ctx.removeFile(...)` | ka-e6eo (BridgeContext) |
| 5 | `updatePebbleFile` | `(uri: String, text: String)` | `ctx.updatePebbleFile(...)` | ka-d994 (BridgePebble) |
| 6 | `removePebbleFile` | `(uri: String)` | `ctx.removePebbleFile(...)` | ka-d994 (BridgePebble) |
| 7 | `analyze` | `(uri: String, version: Int? = null): JsonObject` | `ctx.analyze(...)` | ka-vu27 (BridgeDiagnostics) |
| 8 | `analyzeAll` | `(): JsonObject` | `ctx.analyzeAll()` | ka-vu27 (BridgeDiagnostics) |
| 9 | `hover` | `(uri: String, line: Int, character: Int): JsonObject` | `ctx.hover(...)` | ka-b4j7 (BridgeHover) |
| 10 | `completion` | `(uri: String, line: Int, character: Int, triggerCharacter: String? = null): JsonObject` | `ctx.completion(...)` | ka-zbgz (BridgeCompletion) |
| 11 | `definition` | `(uri: String, line: Int, character: Int): JsonObject` | `ctx.definition(...)` | ka-8vwe (BridgeNavigation) |
| 12 | `implementation` | `(uri: String, line: Int, character: Int): JsonObject` | `ctx.implementation(...)` | ka-8vwe (BridgeNavigation) |
| 13 | `references` | `(uri: String, line: Int, character: Int): JsonObject` | `ctx.references(...)` | ka-9oze (BridgeReferences) |
| 14 | `pebbleDefinition` | `(uri: String, line: Int, character: Int): JsonObject` | `ctx.pebbleDefinition(...)` | ka-d994 (BridgePebble) |
| 15 | `pebbleReferences` | `(uri: String, line: Int, character: Int): JsonObject` | `ctx.pebbleReferences(...)` | ka-d994 (BridgePebble) |
| 16 | `signatureHelp` | `(uri: String, line: Int, character: Int): JsonObject` | `ctx.signatureHelp(...)` | ka-9oze (BridgeSignatureHelp) |
| 17 | `rename` | `(uri: String, line: Int, character: Int, newName: String): JsonObject` | `ctx.rename(...)` | ka-9oze (BridgeRename) |
| 18 | `codeActions` | `(uri: String, line: Int, character: Int): JsonObject` | `ctx.codeActions(...)` | ka-4dsl (BridgeCodeActions) |
| 19 | `workspaceSymbols` | `(query: String): JsonObject` | `ctx.workspaceSymbols(...)` | ka-d994 (BridgeSymbols) |
| 20 | `inlayHints` | `(uri: String, startLine: Int, endLine: Int): JsonObject` | `ctx.inlayHints(...)` | ka-lws3 (BridgeInlayHints) |
| 21 | `codeLens` | `(uri: String): JsonObject` | `ctx.codeLens(...)` | ka-lws3 (BridgeCodeLens) |
| 22 | `semanticTokens` | `(uri: String): JsonObject` | `ctx.semanticTokens(...)` | ka-lws3 (BridgeSemanticTokens) |
| 23 | `callHierarchyPrepare` | `(uri: String, line: Int, character: Int): JsonObject` | `ctx.callHierarchyPrepare(...)` | ka-d994 (BridgeCallHierarchy) |
| 24 | `callHierarchyIncoming` | `(uri: String, line: Int, character: Int, name: String): JsonObject` | `ctx.callHierarchyIncoming(...)` | ka-d994 (BridgeCallHierarchy) |
| 25 | `typeHierarchyPrepare` | `(uri: String, line: Int, character: Int): JsonObject` | `ctx.typeHierarchyPrepare(...)` | ka-d994 (BridgeTypeHierarchy) |
| 26 | `typeHierarchySupertypes` | `(uri: String, line: Int, character: Int, name: String): JsonObject` | `ctx.typeHierarchySupertypes(...)` | ka-d994 (BridgeTypeHierarchy) |

## Companion Object and Runtime Helpers

`CompilerBridgeRuntime` owns the implementations used by `BridgeContext`. `CompilerBridge.Companion`
keeps wrappers for the existing test-facing API:

| Member | Visibility | Referenced by tests |
|--------|-----------|---------------------|
| `CompilerBridge.bundledKotlinVersion()` | `public wrapper` | Yes: `CompilerBridgeTest.kt:14` |
| `CompilerBridge.mapCompilerFlag(flag)` | `public wrapper` | Yes: `CompilerBridgeTest.kt:21-40` |
| `CompilerBridge.findStdlibJarsInRepository(root, version?)` | `internal wrapper` | Yes: `CompilerBridgeTest.kt:59` |
| `CompilerBridgeRuntime.bundledKotlinVersion()` | `public implementation` | Called by wrapper and `BridgeContext` |
| `CompilerBridgeRuntime.mapCompilerFlag(flag)` | `public implementation` | Called by wrapper and `BridgeContext` |
| `CompilerBridgeRuntime.findStdlibJarsInRepository(root, version?)` | `internal implementation` | Called by wrapper and `BridgeContext` |
| `CompilerBridgeRuntime.findFirstJar(versionDir)` | `private implementation helper` | No direct callers outside runtime helper |

**Note on KEYWORD_TOKENS**: `ka-lws3` moves this semantic-token implementation detail out of
`CompilerBridge.Companion` into `BridgeSemanticTokens.kt` as a private file-level value. The final
facade should not expose or import it.

**Note on findStdlibJarsInRepository**: Tests keep calling the `CompilerBridge` companion wrapper,
but `BridgeContext` calls `CompilerBridgeRuntime.findStdlibJarsInRepository(...)` directly. This
avoids a source-level dependency from `BridgeContext` back to the `CompilerBridge` facade.

## Test Impact

Do not restructure tests as part of this facade finalization. Existing tests intentionally remain
API-level regression coverage through `CompilerBridge`, because the facade preserves the public
constructor, instance methods, and companion object methods. If test organization should mirror the
new `Bridge*` files later, create a separate follow-up ticket after this refactoring is complete.

### Files referencing `CompilerBridge` directly

| File | How it uses CompilerBridge | Changes needed |
|------|--------------------------|----------------|
| `CompilerBridgeTest.kt` | Calls companion object methods: `bundledKotlinVersion()`, `mapCompilerFlag()`, `findStdlibJarsInRepository()` | **None** -- companion stays on CompilerBridge |
| `CompilerBridgeIntegrationTest.kt` | Constructs `CompilerBridge()`, calls instance methods | **None** -- constructor and public API unchanged |
| `FixtureDiagnosticsTest.kt` | Constructs `CompilerBridge()` | **None** |
| `PebbleSpringIndexTest.kt` | Constructs `CompilerBridge()` | **None** |
| `PebbleTemplateIndexTest.kt` | Constructs `CompilerBridge()` | **None** |
| `SpringFixtureIntegrationTest.kt` | Constructs `CompilerBridge()` | **None** |
| `SpringWebResolutionTest.kt` | Constructs `CompilerBridge()` (13 times) | **None** |
| `InterfaceNavigationCodeActionIntegrationTest.kt` | Constructs `CompilerBridge()` | **None** |
| `InterfaceSignaturePropagationCodeActionIntegrationTest.kt` | Constructs `CompilerBridge()` | **None** |
| `UnitTestCodeActionIntegrationTest.kt` | Constructs `CompilerBridge()` | **None** |
| `KotlinUnitTestCodeActionIntegrationTest.kt` | Constructs `CompilerBridge()` | **None** |
| `StdlibResolutionTest.kt` | Constructs `CompilerBridge()` | **None** |
| `SessionFileTest.kt` | Constructs `CompilerBridge()` | **None** |
| `DiagnosticStabilityPropertyTest.kt` | Constructs `CompilerBridge()` | **None** |
| `RedeclarationPropertyTest.kt` | Constructs `CompilerBridge()`, references `CompilerBridge.findKtFile` in KDoc | **None** (KDoc reference is informational) |

**Summary**: Zero test files need changes. The facade preserves the exact public API surface -- same class name, same constructor, same method signatures, same companion object methods.

## AnalysisServer Impact

**AnalysisServer.kt needs zero changes.** The call pattern is:

```kotlin
class AnalysisServer(
    private val transport: JsonRpcTransport,
    private val bridge: CompilerBridge = CompilerBridge(),  // default constructor
) {
    // All calls go through the bridge instance:
    bridge.initialize(projectRoot, classpath, compilerFlags, jdkHome, sourceRoots)
    bridge.updateFile(uri, text)
    bridge.hover(uri, line, character)
    bridge.shutdown()
    // ... etc for all 26 methods
}
```

AnalysisServer only calls public methods on `CompilerBridge` and never accesses internals. Since the facade preserves every method signature, AnalysisServer is unaffected.

## Implementation Steps

### Prerequisites

Verify all extraction tickets are complete:

- [ ] ka-e6eo: BridgeContext extracted (foundation)
- [ ] ka-wvv8: BridgeNavigationHelpers extracted (shared interface navigation helpers)
- [ ] ka-4dsl: BridgeCodeActions extracted
- [ ] ka-b4j7: BridgeHover extracted
- [ ] ka-zbgz: BridgeCompletion extracted
- [ ] ka-8vwe: BridgeNavigation extracted
- [ ] ka-vu27: BridgeDiagnostics extracted
- [ ] ka-lws3: BridgeSemanticTokens, InlayHints, CodeLens extracted
- [ ] ka-9oze: BridgeReferences, Rename, SignatureHelp extracted
- [ ] ka-d994: BridgeCallHierarchy, TypeHierarchy, Pebble, Symbols extracted

### Step 1: Audit remaining code in CompilerBridge.kt

After all extractions, CompilerBridge.kt should contain only:
- The class declaration with `internal val ctx = BridgeContext()`
- 26 one-line delegation methods
- The companion object wrappers for `mapCompilerFlag`, `findStdlibJarsInRepository`, and `bundledKotlinVersion`

If any method body, private helper, or private field still remains in CompilerBridge.kt beyond the above, it was missed by an extraction ticket. Identify and move it to the appropriate Bridge* file before proceeding.

### Step 2: Remove extracted code

Delete all method bodies that now delegate to BridgeContext. Replace each with a one-line delegation call. Delete all `private` fields (they moved to BridgeContext). Delete all `private fun` helpers (they moved to Bridge* files). Delete all imports that are no longer needed (the facade only needs `com.google.gson.JsonObject` and whatever the companion needs).

### Step 3: Trim imports

The facade file should need only:
```kotlin
import com.google.gson.JsonObject
import org.jetbrains.kotlin.config.LanguageFeature
import java.nio.file.Path
```

### Step 4: Run tests

```bash
cd sidecar && ./gradlew test
```

All tests must pass with zero changes to test files.

### Step 5: Run Rust tests

```bash
cargo test
```

Rust tests exercise the sidecar over JSON-RPC, so they verify the facade works end-to-end.

### Step 6: Verify line count

```bash
wc -l sidecar/src/main/kotlin/dev/kouros/sidecar/CompilerBridge.kt
```

Target: around or below ~150 lines. Breakdown:
- Package + imports: ~8 lines
- Class declaration + ctx field: ~5 lines
- 26 delegation methods (with spacing/comments): ~60 lines
- Companion object wrappers: ~10 lines
- Total: ~85-100 lines.

### Step 7: Final review

- Confirm no circular dependencies between Bridge* files
- Confirm `BridgeContext` does not reference `CompilerBridge`; runtime/helper calls go through `CompilerBridgeRuntime`
- Confirm each Bridge* file only imports BridgeContext, not other Bridge* files (unless unavoidable shared helpers exist)

## Acceptance Criteria

- [ ] CompilerBridge.kt is ~150 lines (down from 6,204)
- [ ] All 26 public methods delegate to `ctx.*` extension functions
- [ ] Companion object stays on CompilerBridge with wrappers for `bundledKotlinVersion()`, `mapCompilerFlag()`, `findStdlibJarsInRepository()`
- [ ] `CompilerBridgeRuntime` owns the implementations used by `BridgeContext`
- [ ] AnalysisServer.kt has zero changes
- [ ] All test files have zero changes
- [ ] `./gradlew test` passes
- [ ] `cargo test` passes
