---
id: ka-vu27
status: closed
deps: [ka-e6eo]
links: []
created: 2026-04-14T11:53:08Z
type: task
priority: 2
assignee: Jens Kouros
parent: ka-ftb5
tags: [refactoring, sidecar]
---
# Extract BridgeDiagnostics from CompilerBridge

Move analyze() and analyzeAll() to BridgeDiagnostics.kt (~274 lines)

## Methods to Extract

### Public methods (become `internal` extension functions on `BridgeContext`)

| Method | Lines | Signature |
|--------|-------|-----------|
| `analyze` | 477–623 (147 lines) | `fun analyze(uri: String, version: Int? = null): JsonObject` |
| `analyzeAll` | 629–745 (117 lines) | `fun analyzeAll(): JsonObject` |

### Private helper (moves into BridgeDiagnostics.kt as a `private` function)

| Method | Lines | Signature |
|--------|-------|-----------|
| `collectDeclaredNames` | 5821–5843 (23 lines) | `private fun collectDeclaredNames(ktFile: KtFile): Set<String>` |

`collectDeclaredNames` is only called from `analyze()` (line 523). No other feature uses it, so it stays private inside BridgeDiagnostics.kt.

**Total: ~287 lines** (147 + 117 + 23)

## Dependencies on BridgeContext

Both methods rely on shared state and utilities that will live on `BridgeContext` after ka-e6eo:

### Fields accessed
- `session: StandaloneAnalysisAPISession?` — read in both methods
- `sessionDirty: Boolean` — read in `analyze()` (via `ensureSessionCurrent()`)
- `virtualFiles: MutableMap<String, String>` — read in `analyzeAll()` via `hasVirtualFile()`
- `virtualFileTempDir: Path?` — read in `analyze()` to skip decompiled stubs
- `symbolIndex: SymbolIndex` — used transitively via `planTypeImportAssist` → `findImportCandidates`

### Utility methods called (all on BridgeContext)
- `ensureSessionCurrent()` — called at top of `analyze()`
- `findKtFile(session, uri)` — resolve URI to KtFile
- `findKtFileInSession(session, uri)` — check session content vs virtual content
- `hasVirtualFile(uri)` — used in `analyzeAll()` to prefer virtual content
- `getVirtualFileContent(uri)` — used in `analyze()` to detect virtual file divergence
- `uriToPath(uri)` — strip `file://` prefix

### Import-assist methods called (shared with BridgeCodeActions)
- `planTypeImportAssist(ktFile, factoryName, startOffset, endOffset)` — find single-candidate import fix
- `buildImportEdit(uri, ktFile, document, fqn)` — build the actual text edit

These two methods are also used by `addTypeImportCodeActions()` in BridgeCodeActions, so they belong on BridgeContext as shared utilities (not in BridgeDiagnostics).

### External APIs used (imports needed in BridgeDiagnostics.kt)
- `org.jetbrains.kotlin.analysis.api.analyze` — the top-level `analyze(ktFile) { ... }` scope function
- `org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter`
- `org.jetbrains.kotlin.psi.KtFile`, `KtClass`, `KtClassOrObject`, `KtNamedFunction`, `KtProperty`, `KtTypeAlias`, `KtNamedDeclaration`
- `com.google.gson.JsonArray`, `com.google.gson.JsonObject`

## Implementation Steps

### Step 1: Create BridgeDiagnostics.kt

Create `sidecar/src/main/kotlin/dev/kouros/sidecar/BridgeDiagnostics.kt` in the same package.

```kotlin
package dev.kouros.sidecar
```

### Step 2: Move `collectDeclaredNames` as a private file-level function

Copy the method body (lines 5821–5843) into BridgeDiagnostics.kt as a private top-level function (not an extension — it has no need for BridgeContext access):

```kotlin
private fun collectDeclaredNames(ktFile: KtFile): Set<String> { ... }
```

Delete lines 5816–5843 from CompilerBridge.kt.

### Step 3: Convert `analyze(uri, version)` to an extension function

Move lines 477–623 into BridgeDiagnostics.kt as:

```kotlin
internal fun BridgeContext.analyze(uri: String, version: Int? = null): JsonObject {
    ensureSessionCurrent()
    // ... body unchanged, all field/method references resolve via BridgeContext receiver ...
}
```

Key references that resolve through `BridgeContext`:
- `ensureSessionCurrent()`, `virtualFileTempDir`, `uriToPath()`, `session`
- `findKtFile()`, `findKtFileInSession()`, `getVirtualFileContent()`
- `planTypeImportAssist()`, `buildImportEdit()`

Delete lines 474–623 from CompilerBridge.kt.

### Step 4: Convert `analyzeAll()` to an extension function

Move lines 629–745 into BridgeDiagnostics.kt as:

```kotlin
internal fun BridgeContext.analyzeAll(): JsonObject {
    // ... body unchanged ...
}
```

Key references that resolve through `BridgeContext`:
- `session`, `hasVirtualFile()`, `findKtFile()`

Delete lines 624–745 from CompilerBridge.kt.

### Step 5: Add delegation in CompilerBridge facade

In CompilerBridge.kt (the thin facade that remains after ka-e6eo), add delegation methods:

```kotlin
fun analyze(uri: String, version: Int? = null): JsonObject = ctx.analyze(uri, version)
fun analyzeAll(): JsonObject = ctx.analyzeAll()
```

Where `ctx` is the `BridgeContext` instance held by the facade.

### Step 6: Verify

```bash
cd sidecar && ./gradlew test
cargo test
```

All existing tests must pass unchanged. `AnalysisServer.kt` calls `compilerBridge.analyze(...)` and `compilerBridge.analyzeAll()` — the public API is preserved by the facade delegation.

## Shared code note

The diagnostic-to-JSON conversion logic (severity, message, code, line/column extraction from document offsets) is duplicated between `analyze()` (lines 563–594) and `analyzeAll()` (lines 689–719). This is a candidate for a shared private helper like `diagnosticToJson(diagnostic, document): JsonObject` inside BridgeDiagnostics.kt. However, deduplication is out of scope for this extraction ticket — the goal is a pure move with no behavioral changes.

## Risk

Low. This is a mechanical extraction:
- No logic changes
- No new dependencies
- `collectDeclaredNames` is fully self-contained (only uses KtFile PSI traversal)
- Both public methods have clear boundaries and no interleaving with other features
