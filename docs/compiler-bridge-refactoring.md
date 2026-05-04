# CompilerBridge.kt Refactoring Analysis

## Current State

`CompilerBridge.kt` is **6,204 lines** -- a monolithic class implementing all 26 LSP features for the Kotlin analysis sidecar. Key metrics:

- **26 public methods** (LSP feature implementations)
- **101 private methods**
- **17 mutable state fields**
- **Largest method**: `codeActions()` at 1,276 lines
- **Called exclusively by**: `AnalysisServer.kt`

## Problems Identified

### 1. CompilerBridge.kt is a God Object

Every LSP feature (hover, completion, diagnostics, code actions, etc.) lives in a single class with deeply shared mutable state. This makes it:
- Hard to navigate and understand
- Risky to modify (changes in one feature can break another)
- Impossible to work on multiple features in parallel without merge conflicts

### 2. Code Actions is Massive

The `codeActions()` method alone is 1,276 lines implementing 11 distinct refactoring actions. These are largely independent of each other but are all packed into one method.

### 3. Feature-Specific Helpers are Scattered

Helper functions for a given feature (e.g., hover) are spread across the file rather than grouped together. For example, hover helpers span lines 751-775 (public method) and 3789-4405 (private helpers), with 3,000+ lines of unrelated code in between.

### 4. Rust server.rs is Also Large (Minor)

At 3,529 lines, `server.rs` is large but better organized than CompilerBridge.kt. It could benefit from extraction but is not urgent.

## Functional Areas in CompilerBridge.kt

| Area | Lines | Key Methods |
|------|-------|-------------|
| Session & File Management | ~400 | `initialize()`, `updateFile()`, `removeFile()`, `ensureSessionCurrent()` |
| Code Actions | ~1,276 | `codeActions()` + 11 action builders |
| Hover | ~617 | `hover()`, `hoverOnce()`, `buildDeclarationHover()`, etc. |
| Completion | ~376 | `completion()`, `completionOnce()`, 12+ completion helpers |
| Definition & Navigation | ~305 | `definition()`, `buildDefinitionLocation()`, etc. |
| Diagnostics | ~274 | `analyze()`, `analyzeAll()` |
| Semantic Tokens | ~167 | `semanticTokens()`, token classifiers |
| Inlay Hints | ~175 | `inlayHints()` |
| References | ~147 | `references()`, `countReferencesInFile()` |
| Rename | ~126 | `rename()` |
| Signature Help | ~116 | `signatureHelp()`, `computeActiveParameter()` |
| Code Lens | ~112 | `codeLens()` |
| Call Hierarchy | ~180 | `callHierarchyPrepare()`, `callHierarchyIncoming()` |
| Type Hierarchy | ~155 | `typeHierarchyPrepare()`, `typeHierarchySupertypes()` |
| Pebble Support | ~100 | `pebbleDefinition()`, `pebbleReferences()` |
| Workspace Symbols | ~30 | `workspaceSymbols()` |
| Core Utilities | ~650 | File resolution, path mapping, offset conversion, PSI helpers |

## Shared Mutable State (17 fields)

```
session, sourceModule, disposable                          // analysis session
virtualFiles, virtualFileTempDir, virtualFilesOnDisk,      // file management
  virtualFileDiskPaths, dirtyOnDiskFiles, shadowPathMapping,
  lightFileCache
classpathFqns, decompiledFileCache                         // classpath
pebbleDocuments, pebbleIndex, pebbleSpringIndex,           // Pebble templates
  pebbleSpringIndexDirty
symbolIndex                                                // symbols
initProjectRoot, initClasspath, initCompilerFlags,         // init params
  initJdkHome, initSourceRoots
```

## Recommended Approach: BridgeContext + Extension Functions

### Why This Approach

- **Not pure facade+delegates**: The 17 mutable fields are deeply shared. Delegate classes would each need references to the same state, creating awkward shared-ownership with no real encapsulation.
- **Not pure extension functions on CompilerBridge**: Would require all 17 fields to be `internal`, leaking all mutable state.
- **BridgeContext + extensions**: Extract shared state into a `BridgeContext` class. Feature implementations become extension functions on `BridgeContext` in separate files. `CompilerBridge` remains as a thin facade.

### Target Architecture

```
CompilerBridge.kt (~200 lines, facade + companion object)
  └── BridgeContext.kt (~1,050 lines, shared state + core utilities)
        ├── BridgeCodeActions.kt      (~1,276 lines)
        ├── BridgeHover.kt            (~617 lines)
        ├── BridgeCompletion.kt       (~376 lines)
        ├── BridgeDefinition.kt       (~305 lines)
        ├── BridgeDiagnostics.kt      (~274 lines)
        ├── BridgeSemanticTokens.kt   (~167 lines)
        ├── BridgeInlayHints.kt       (~175 lines)
        ├── BridgeReferences.kt       (~147 lines)
        ├── BridgeRename.kt           (~126 lines)
        ├── BridgeSignatureHelp.kt    (~116 lines)
        ├── BridgeCodeLens.kt         (~112 lines)
        ├── BridgeCallHierarchy.kt    (~180 lines)
        ├── BridgeTypeHierarchy.kt    (~155 lines)
        ├── BridgePebble.kt           (~100 lines)
        └── BridgeSymbols.kt          (~30 lines)
```

### BridgeContext Contains

- All 17 mutable state fields (`internal` visibility)
- Session lifecycle: `initialize()`, `shutdown()`, `updateFile()`, `removeFile()`
- Core utilities used by multiple features: `findKtFile()`, `lineColToOffset()`, `selectionForOffset()`, `buildFileUri()`, `ensureSessionCurrent()`, `extractKDocText()`, `findImportCandidates()`, etc.

### Extension Function Pattern

```kotlin
// BridgeHover.kt
package dev.kouros.sidecar

internal fun BridgeContext.hover(uri: String, line: Int, character: Int): JsonObject {
    val result = hoverOnce(uri, line, character)
    // ...
    return result
}

private fun BridgeContext.hoverOnce(uri: String, line: Int, character: Int): JsonObject {
    val ktFile = findKtFile(uri) ?: return JsonObject()
    // access session, virtualFiles, etc. directly
}
```

### CompilerBridge Facade

```kotlin
// CompilerBridge.kt (~200 lines)
class CompilerBridge {
    private val ctx = BridgeContext()

    fun hover(uri: String, line: Int, character: Int) = ctx.hover(uri, line, character)
    fun completion(uri: String, line: Int, character: Int) = ctx.completion(uri, line, character)
    // ... one line per feature ...

    companion object {
        fun bundledKotlinVersion(): String = SidecarRuntime.kotlinVersion
        fun mapCompilerFlag(flag: String): LanguageFeature? = ...
        fun findStdlibJarsInRepository(): List<Path> = ...
    }
}
```

## Execution Order

This refactoring is **sequential** (all units modify code originating from the same file):

1. **Extract BridgeContext** -- foundation; all other work depends on this
2. **Extract BridgeCodeActions.kt** -- largest single win (1,276 lines)
3. **Extract BridgeHover.kt** and **BridgeCompletion.kt** -- next largest features
4. **Extract remaining features** (Definition, References, Diagnostics, etc.) in any order
5. **Finalize CompilerBridge facade** -- slim to ~200 lines

## Impact

- **AnalysisServer.kt**: Zero changes (facade preserves the public API)
- **Tests**: Zero changes (CompilerBridge public API unchanged, companion object stays)
- **Result**: CompilerBridge.kt drops from 6,204 to ~200 lines. Largest new file is BridgeCodeActions.kt at ~1,276 lines. Most files are 100-400 lines.

## Cross-Cutting Method Placement

| Method | Used by | Place in |
|--------|---------|----------|
| `findKtFile()` | All features | BridgeContext |
| `lineColToOffset()` | All features | BridgeContext |
| `selectionForOffset()` | CodeActions, Diagnostics, References, Rename | BridgeContext |
| `extractKDocText()` | Hover, CodeLens | BridgeContext |
| `buildFileUri()` | Definition, References, Rename, CodeLens, Hierarchy | BridgeContext |
| `findImportCandidates()` | CodeActions, Completion | BridgeContext |
| `makeEdit()` | CodeActions only | BridgeCodeActions.kt |

## Risks and Mitigations

- **KaSession extension functions**: Some private methods are `KaSession.buildX()`. These become private functions in feature files taking BridgeContext as an explicit parameter.
- **Companion object**: Tests reference `CompilerBridge.mapCompilerFlag()`. Keep companion on CompilerBridge, not BridgeContext.
- **Merge conflicts during refactoring**: Execute sequentially, not in parallel branches.

## Verification

```bash
cd sidecar && ./gradlew test    # Kotlin unit + integration tests
cargo test                       # Rust unit tests
```
