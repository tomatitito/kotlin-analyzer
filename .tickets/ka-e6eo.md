---
id: ka-e6eo
status: closed
deps: []
links: []
created: 2026-04-14T11:53:08Z
type: task
priority: 1
assignee: Jens Kouros
parent: ka-ftb5
tags: [refactoring, sidecar]
---
# Extract BridgeContext from CompilerBridge

Extract shared state (17+ fields) and core utility methods into BridgeContext.kt (~1,050 lines).
All other feature extraction tickets depend on this one.

## Design

### State Fields to Extract

All fields move from `CompilerBridge` to `BridgeContext` with `internal` visibility. Current visibility in CompilerBridge is `private` for all.

| # | Field | Type | Initial value |
|---|-------|------|---------------|
| 1 | `session` | `StandaloneAnalysisAPISession?` | `null` |
| 2 | `sourceModule` | `KaModule?` | `null` |
| 3 | `disposable` | `Disposable` | `Disposer.newDisposable("compiler-bridge")` |
| 4 | `virtualFiles` | `MutableMap<String, String>` | `mutableMapOf()` |
| 5 | `pebbleDocuments` | `PebbleDocumentStore` | `PebbleDocumentStore()` |
| 6 | `pebbleIndex` | `PebbleTemplateIndex` | `PebbleTemplateIndex()` |
| 7 | `pebbleSpringIndex` | `PebbleSpringIndex` | `PebbleSpringIndex(pebbleIndex)` |
| 8 | `symbolIndex` | `SymbolIndex` | `SymbolIndex()` |
| 9 | `classpathFqns` | `Set<String>` | `setOf()` |
| 10 | `pebbleSpringIndexDirty` | `Boolean` | `true` |
| 11 | `virtualFileTempDir` | `Path?` | `null` |
| 12 | `virtualFilesOnDisk` | `MutableSet<String>` | `mutableSetOf()` |
| 13 | `virtualFileDiskPaths` | `MutableMap<String, String>` | `mutableMapOf()` |
| 14 | `decompiledFileCache` | `MutableMap<String, Path>` | `mutableMapOf()` |
| 15 | `sessionDirty` | `Boolean` | `false` |
| 16 | `dirtyOnDiskFiles` | `MutableSet<String>` | `mutableSetOf()` |
| 17 | `shadowPathMapping` | `MutableMap<String, String>` | `mutableMapOf()` |
| 18 | `initProjectRoot` | `String` | `""` |
| 19 | `initClasspath` | `List<String>` | `emptyList()` |
| 20 | `initCompilerFlags` | `List<String>` | `emptyList()` |
| 21 | `initJdkHome` | `String` | `""` |
| 22 | `initSourceRoots` | `List<String>` | `emptyList()` |
| 23 | `lightFileCache` | `MutableMap<String, Pair<String, KtFile>>` | `mutableMapOf()` |

### Core Utility Methods to Extract

Each method below is used by 2+ feature areas, justifying placement in BridgeContext rather than a specific feature file.

#### File lookup methods

| Method | Signature | Lines | Callers |
|--------|-----------|-------|---------|
| `findKtFile` | `(session: StandaloneAnalysisAPISession, uri: String): KtFile?` | 5713-5813 | analyze, hover, completion, definition, references, signatureHelp, rename, codeActions, inlayHints, codeLens, semanticTokens, callHierarchy, typeHierarchy |
| `findKtFileInSession` | `(session: StandaloneAnalysisAPISession, uri: String): KtFile?` | 5483-5492 | analyze (virtual file content check), ensurePebbleSpringIndexCurrent |
| `allSessionKtFiles` | `(): List<KtFile>` | 5863-5868 | interface navigation (findImplementationNavigationTargets), references (analyzeAll iteration) |
| `fileUriForKtFile` | `(ktFile: KtFile): String?` | 5870-5874 | interface navigation (navigationTargetFor) |

#### URI / path conversion

| Method | Signature | Lines | Callers |
|--------|-----------|-------|---------|
| `uriToPath` | `(uri: String): String` | 5845-5851 | findKtFile, findKtFileInSession, updateFile, writeVirtualFileToDisk, unitTest planner, getVirtualFileEntry |
| `buildFileUri` | `(vfPath: String): String` | 1274-1282 | buildDefinitionLocation, buildPsiDefinitionLocation, fileUriForKtFile |

#### Offset / position conversion

| Method | Signature | Lines | Callers |
|--------|-----------|-------|---------|
| `lineColToOffset` | `(ktFile: KtFile, line: Int, character: Int): Int?` | 5894-5946 | hover, completion, definition, references, signatureHelp, rename, codeActions, callHierarchyPrepare, callHierarchyIncoming, typeHierarchyPrepare, typeHierarchySupertypes |
| `offsetToLineCol` | `(document: Document, offset: Int): Pair<Int, Int>` | 2548-2555 | moveToCompanion, moveFromCompanion, moveToTopLevel, convertToExtension (code action helpers) |
| `selectionForOffset` | `(document: Document, offset: Int, length: Int = 0): JsonObject` | 5876-5892 | interface navigation (navigationTargetFor) |

#### Session lifecycle

| Method | Signature | Lines | Callers |
|--------|-----------|-------|---------|
| `initialize` | `(projectRoot: String, classpath: List<String>, compilerFlags: List<String>, jdkHome: String, sourceRoots: List<String>)` | 84-303 | AnalysisServer dispatch, ensureSessionCurrent |
| `shutdown` | `()` | 3769-3787 | AnalysisServer dispatch |
| `updateFile` | `(uri: String, text: String)` | 308-368 | AnalysisServer dispatch |
| `removeFile` | `(uri: String)` | 373-385 | AnalysisServer dispatch |
| `ensureSessionCurrent` | `(): Unit` | 446-450 | hover, completion, definition, codeActions (called before analysis) |
| `ensurePebbleSpringIndexCurrent` | `(): Unit` | 452-472 | pebbleDefinition, definition, references |

`updatePebbleFile()` and `removePebbleFile()` are final-owned by `ka-d994` in
`BridgePebble.kt`. `BridgeContext` owns the Pebble state they mutate, but this foundation ticket
should not make those methods permanent BridgeContext lifecycle methods unless needed temporarily
while keeping the code compiling between extraction steps.

#### Virtual file management

| Method | Signature | Lines | Callers |
|--------|-----------|-------|---------|
| `writeVirtualFileToDisk` | `(uri: String, text: String)` | 5560-5589 | initialize, updateFile |
| `writeDecompiledFile` | `(vfPath: String, text: String): Path?` | 5597-5626 | buildDefinitionLocation, renderLibrarySymbolStub |
| `buildShadowSourceRoots` | `(baseSourceRoots: List<Path>): List<Path>` | 5512-5554 | initialize |
| `getVirtualFileEntry` | `(uri: String): Pair<String, String>?` | 5853-5857 | findKtFile, getVirtualFileContent |
| `getVirtualFileContent` | `(uri: String): String?` | 5859 | analyze (virtual file check), hasVirtualFile |
| `hasVirtualFile` | `(uri: String): Boolean` | 5861 | analyzeAll, buildShadowSourceRoots |

#### PSI helpers (used by multiple features)

| Method | Signature | Lines | Callers |
|--------|-----------|-------|---------|
| `findHoverElement` | `(ktFile: KtFile, line: Int, offset: Int): PsiElement?` | 5948-5969 | hover |
| `findTargetDeclaration` | `(element: PsiElement): PsiElement?` | 5061-5080 | references, rename |
| `findEnclosingDeclaration` | `(element: PsiElement?): KtDeclaration?` | 5135-5143 | codeActions (suppress warning) |
| `extractKDocText` | `(declaration: KtDeclaration): String?` | 5384-5421 | hover (declaration + reference + annotation), signatureHelp |

`collectDeclaredNames()` is diagnostics-only and final-owned by `ka-vu27` in
`BridgeDiagnostics.kt`.

#### Import helpers

| Method | Signature | Lines | Callers |
|--------|-----------|-------|---------|
| `findImportCandidates` | `(ktFile: KtFile, shortName: String): List<ImportCandidate>` | 2466-2485 | codeActions (import code action), analyze (auto-import) |
| `planTypeImportAssist` | `(ktFile: KtFile, factoryName: String?, startOffset: Int, endOffset: Int): TypeImportAssistPlan?` | near import helpers | analyze (auto-import), codeActions (type import assists) |
| `buildImportEdit` | `(uri: String, ktFile: KtFile, document: Document, fqn: String): JsonObject?` | 2487-2506 | analyze (auto-import edit), codeActions (import code action) |
| `computeImportInsertion` | `(ktFile: KtFile, document: Document, fqn: String): Pair<Int, String>` | 2508-2531 | buildImportEdit, collectUnimportedCompletionCandidates |

#### Session infrastructure

| Method | Signature | Lines | Callers |
|--------|-----------|-------|---------|
| `registerVirtualFileModuleProvider` | `(): Unit` | 5431-5477 | initialize |
| `findKotlinStdlibJars` | `(): List<Path>` | 6005-6048 | initialize |
| `findSourceRoots` | `(projectRoot: Path): List<Path>` | 6050-6077 | initialize |
| `buildClasspathFqnIndex` | `(jars: List<String>): Set<String>` | 5974-6003 | initialize |
| `updateFileInSession` | `(uri: String, text: String)` | 5499-5501 | updateFile |

#### KaSession extension methods

No feature-specific KaSession extension helper is final-owned by `BridgeContext`.
`renderLibrarySymbolStub()` belongs to `ka-8vwe` in `BridgeNavigation.kt`.

#### Edit helpers

| Method | Signature | Lines | Callers |
|--------|-----------|-------|---------|
| `makeEdit` | `(uri: String, startLine: Int, startCol: Int, endLine: Int, endCol: Int, newText: String): JsonObject` | 2361-2379 | buildImportEdit, codeActions (suppress, moveToCompanion, moveFromCompanion, moveToTopLevel, convertToExtension) |

#### Data classes

| Class | Lines | Used by |
|-------|-------|---------|
| `ImportCandidate` | 2270 | findImportCandidates, planTypeImportAssist, addTypeImportCodeActions |
| `TypeImportAssistPlan` | 2271-2274 | planTypeImportAssist, addTypeImportCodeActions, analyze |

#### Runtime helper methods

| Item | Current lines | Used by |
|------|---------------|---------|
| `bundledKotlinVersion()` | 6143 | findKotlinStdlibJars |
| `findStdlibJarsInRepository()` | 6145-6170 | findKotlinStdlibJars |
| `findFirstJar()` | 6172-6179 | findStdlibJarsInRepository |
| `mapCompilerFlag()` | 6181-6202 | initialize |

Move the implementations into a neutral `CompilerBridgeRuntime.kt` object so `BridgeContext` does
not depend on the `CompilerBridge` facade:

```kotlin
internal object CompilerBridgeRuntime {
    fun bundledKotlinVersion(): String = SidecarRuntime.kotlinVersion
    internal fun findStdlibJarsInRepository(repositoryRoot: Path, preferVersion: String? = null): List<Path> { ... }
    private fun findFirstJar(versionDir: Path): Path? { ... }
    fun mapCompilerFlag(flag: String): LanguageFeature? { ... }
}
```

Keep compatibility wrappers on `CompilerBridge.Companion` because tests reference the
`CompilerBridge` API:

```kotlin
companion object {
    fun bundledKotlinVersion(): String = CompilerBridgeRuntime.bundledKotlinVersion()
    internal fun findStdlibJarsInRepository(repositoryRoot: Path, preferVersion: String? = null): List<Path> =
        CompilerBridgeRuntime.findStdlibJarsInRepository(repositoryRoot, preferVersion)
    fun mapCompilerFlag(flag: String): LanguageFeature? = CompilerBridgeRuntime.mapCompilerFlag(flag)
}
```

`BridgeContext.initialize()` and `findKotlinStdlibJars()` should call `CompilerBridgeRuntime`
directly, not `CompilerBridge.Companion`.

`KEYWORD_TOKENS` is final-owned by `ka-lws3` in `BridgeSemanticTokens.kt` as a file-level value,
unless the implementation deliberately chooses the alternate approach documented in `ka-xwq9`.

### The `analyze{}` Pattern

The `analyze(ktFile) { ... }` call is a top-level function imported from `org.jetbrains.kotlin.analysis.api.analyze`. It:

1. Takes a `KtElement` (typically a `KtFile`) and a lambda `KaSession.() -> T`
2. Creates a `KaSession` scoped to that element's module
3. Executes the lambda within that session context
4. Returns the lambda's result

Inside the lambda, `this` is a `KaSession`, which provides:
- Extension properties on PSI elements (e.g., `KtProperty.symbol`, `KtExpression.expressionType`)
- Resolution methods (e.g., `KtReference.resolveToSymbol()`, `KtCallExpression.resolveToCall()`)
- Scope access (e.g., `KtFile.scopeContext()`, `KaType.scope`)
- Type rendering (e.g., `KaType.render()`)

Methods declared as `KaSession.extensionMethod()` can only be called inside an `analyze{}` block. Feature files will call `analyze(ktFile) { ... }` directly (they import the same top-level function). BridgeContext utility methods that need `KaSession` are declared as `KaSession.methodName()` extensions and called from within feature-level `analyze{}` blocks.

Example of how a feature file uses the pattern:

```kotlin
// In HoverFeature.kt
fun hoverOnce(uri: String, line: Int, character: Int): JsonObject {
    val ktFile = ctx.findKtFile(ctx.session!!, uri) ?: return ...
    analyze(ktFile) {
        val offset = ctx.lineColToOffset(ktFile, line, character) ?: return@analyze
        val element = ctx.findHoverElement(ktFile, line, offset)
        // ... KaSession extension calls like element.expressionType ...
        // ... ctx.extractKDocText(declaration) (does not need KaSession) ...
        // Feature-specific KaSession helpers live in feature files, not BridgeContext.
    }
}
```

### Import Statements

BridgeContext.kt will need the following imports:

```kotlin
package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.parents
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
```

### Implementation Steps

#### Step 1: Create BridgeContext.kt with state fields

Create `sidecar/src/main/kotlin/dev/kouros/sidecar/BridgeContext.kt` with the class declaration and all 23 state fields listed above. All fields use `internal` visibility.

```kotlin
class BridgeContext {
    internal var session: StandaloneAnalysisAPISession? = null
    internal var sourceModule: KaModule? = null
    internal var disposable = Disposer.newDisposable("compiler-bridge")
    // ... all 23 fields ...
}
```

#### Step 2: Move session lifecycle methods

Move `initialize()`, `shutdown()`, `updateFile()`, `removeFile()`, `ensureSessionCurrent()`, `ensurePebbleSpringIndexCurrent()` into BridgeContext. These are the core lifecycle methods that manage shared state directly.

`updatePebbleFile()` and `removePebbleFile()` move later with `ka-d994` into `BridgePebble.kt`.
Only expose the underlying Pebble fields and dirty flag from `BridgeContext`.

Change visibility from `private` to `internal` where needed (e.g., `ensureSessionCurrent`, `ensurePebbleSpringIndexCurrent`).

#### Step 3: Move infrastructure helpers

Move in this order (respecting call dependencies -- callees first):

1. `uriToPath`, `buildFileUri` (leaf utilities, no dependencies)
2. `getVirtualFileEntry`, `getVirtualFileContent`, `hasVirtualFile` (depend on uriToPath, virtualFiles)
3. `findKotlinStdlibJars` (calls helpers on `CompilerBridgeRuntime`)
4. `findSourceRoots`, `buildClasspathFqnIndex` (depend on Path/File operations)
5. `writeVirtualFileToDisk`, `writeDecompiledFile`, `buildShadowSourceRoots` (depend on uriToPath, virtual file state)
6. `registerVirtualFileModuleProvider` (depends on session, sourceModule)
7. `updateFileInSession` (trivial)

#### Step 4: Move file lookup methods

Move `findKtFileInSession`, `findKtFile`, `allSessionKtFiles`, `fileUriForKtFile`. These depend on `uriToPath`, `shadowPathMapping`, `virtualFiles`, `getVirtualFileEntry`, and `lightFileCache`.

#### Step 5: Move offset/position methods

Move `lineColToOffset`, `offsetToLineCol`, `selectionForOffset`. Pure utility, no state dependencies.

#### Step 6: Move PSI helpers

Move `findHoverElement`, `findTargetDeclaration`, `findEnclosingDeclaration`, `extractKDocText`.
Do not move `collectDeclaredNames`; it is diagnostics-only and moves with `ka-vu27`.

#### Step 7: Move import helpers

Move `ImportCandidate` and `TypeImportAssistPlan` data classes, then `findImportCandidates`,
`planTypeImportAssist`, `computeImportInsertion`, `buildImportEdit`, `makeEdit`.

#### Step 8: Do not move feature KaSession extensions

`BridgeContext` should not final-own feature-specific KaSession extension helpers.
`renderLibrarySymbolStub` moves with `ka-8vwe` into `BridgeNavigation.kt`.

#### Step 9: Extract runtime helpers and keep compatibility wrappers

Create `CompilerBridgeRuntime.kt` and move the implementations of `bundledKotlinVersion()`,
`findStdlibJarsInRepository()`, `findFirstJar()`, and `mapCompilerFlag()` there. Add wrappers on
`CompilerBridge.Companion` for the test-facing API, but update `BridgeContext` callers to use
`CompilerBridgeRuntime.*` directly. Do not move these helpers to `BridgeContext.Companion`.

`KEYWORD_TOKENS` is owned by `ka-lws3` if semantic tokens move it into `BridgeSemanticTokens.kt`.

#### Step 10: Update CompilerBridge to delegate through ctx

Add `internal val ctx = BridgeContext()` to CompilerBridge. Update all remaining feature methods (hover, completion, definition, references, etc.) to access state and utilities through `ctx.*`. Each feature method remains in CompilerBridge for now -- subsequent tickets extract them into feature files.

Example transformation:
```kotlin
// Before
val currentSession = session ?: return result
val ktFile = findKtFile(currentSession, uri) ?: return result

// After
val currentSession = ctx.session ?: return result
val ktFile = ctx.findKtFile(currentSession, uri) ?: return result
```

#### Step 11: Verify compilation and tests

1. Run `./gradlew :sidecar:compileKotlin` to verify BridgeContext.kt compiles
2. Run `./gradlew :sidecar:compileTestKotlin` to verify test compilation
3. Run the full sidecar test suite
4. Run the Rust integration tests

### Acceptance Criteria

- [ ] `BridgeContext.kt` exists and compiles independently
- [ ] `CompilerBridge.kt` compiles, holding `internal val ctx = BridgeContext()` and delegating all state access / utility calls through it
- [ ] All sidecar tests pass (`./gradlew :sidecar:test`)
- [ ] All Rust integration tests pass (`cargo test`)
- [ ] No public API changes -- `CompilerBridge` still exposes the same public methods with the same signatures
- [ ] No feature methods moved yet -- only state and utilities. Feature methods (hover, completion, definition, etc.) remain in CompilerBridge and are extracted in follow-up tickets
