---
id: ka-4dsl
status: closed
deps: [ka-e6eo, ka-wvv8]
links: []
created: 2026-04-14T11:53:08Z
type: task
priority: 2
assignee: Jens Kouros
parent: ka-ftb5
tags: [refactoring, sidecar]
---
# Extract BridgeCodeActions from CompilerBridge

Move codeActions() and 11 action builders to BridgeCodeActions.kt (~1,276 lines)

## Methods to Extract

### Public Entry Point

| Method | Signature | Lines | KaSession Extension |
|--------|-----------|-------|---------------------|
| `codeActions` | `fun codeActions(uri: String, line: Int, character: Int): JsonObject` | 1678-1819 | No (calls `analyze` internally) |

### Action Builders

| Method | Signature | Lines | KaSession Extension |
|--------|-----------|-------|---------------------|
| `addInterfaceNavigationActions` | `private fun KaSession.addInterfaceNavigationActions(element: PsiElement, actionsArray: JsonArray)` | 1827-1868 | Yes |
| `addExplicitTypeAction` | `private fun KaSession.addExplicitTypeAction(element: PsiElement, document: Document, fileUri: String, actionsArray: JsonArray)` | 2056-2102 | Yes |
| `addConvertBodyAction` | `private fun addConvertBodyAction(element: PsiElement, document: Document, fileUri: String, actionsArray: JsonArray)` | 2107-2212 | No |
| `addOrganizeImportsAction` | `private fun addOrganizeImportsAction(ktFile: KtFile, document: Document, fileUri: String, actionsArray: JsonArray)` | 2217-2264 | No |
| `addTypeImportCodeActions` | `private fun addTypeImportCodeActions(uri: String, ktFile: KtFile, document: Document, factoryName: String?, startOffset: Int, endOffset: Int, actionsArray: JsonArray)` | 2381-2404 | No |
| `addMoveToCompanionAction` | `private fun KaSession.addMoveToCompanionAction(element: PsiElement, document: Document, fileUri: String, actionsArray: JsonArray)` | 2619-2689 | Yes |
| `addMoveFromCompanionAction` | `private fun addMoveFromCompanionAction(element: PsiElement, document: Document, fileUri: String, actionsArray: JsonArray)` | 2696-2768 | No |
| `addMoveToTopLevelAction` | `private fun KaSession.addMoveToTopLevelAction(element: PsiElement, document: Document, fileUri: String, actionsArray: JsonArray, ktFile: KtFile)` | 2776-2889 | Yes |
| `addConvertToExtensionFunctionAction` | `private fun KaSession.addConvertToExtensionFunctionAction(element: PsiElement, document: Document, fileUri: String, actionsArray: JsonArray)` | 2897-2947 | Yes |
| `addOpenOrCreateUnitTestAction` | `private fun addOpenOrCreateUnitTestAction(element: PsiElement, fileUri: String, actionsArray: JsonArray)` | 1943-1963 | No |

Note: `addSuppressWarningAction` is not a standalone method. The suppress-warning logic is inlined in `codeActions()` at lines 1731-1765. It stays inline.

### Shared Navigation Helpers

Do **not** move shared interface navigation discovery helpers into `BridgeCodeActions.kt`.
They are owned by `ka-wvv8` in `BridgeNavigationHelpers.kt` because both code actions and
`implementation()` use them.

`BridgeCodeActions.kt` should call these internal helpers from `BridgeNavigationHelpers.kt`:

- `NavigationTarget`
- `KaSession.findImplementationNavigationTargets(ctx, interfaceDeclaration)`
- `KaSession.findInterfaceNavigationTargets(ctx, classOrObject)`
- `isConcreteImplementationCandidate(candidate)`
- `navigationTargetFor(ctx, declaration, title)`

### Edit Helpers

| Method | Signature | Lines | KaSession Extension |
|--------|-----------|-------|---------------------|
| `makeEdit` | `private fun makeEdit(uri: String, startLine: Int, startCol: Int, endLine: Int, endCol: Int, newText: String): JsonObject` | 2361-2379 | No |
| `makeDeleteEdit` | `private fun makeDeleteEdit(document: Document, fileUri: String, element: PsiElement): JsonObject` | 2560-2573 | No |
| `makeAppendToFileEdit` | `private fun makeAppendToFileEdit(document: Document, fileUri: String, text: String): JsonObject` | 2578-2586 | No |

### Shared Import Helpers

Do **not** move import-assist helpers into `BridgeCodeActions.kt`. Code actions calls these shared
helpers from `BridgeContext`:

- `planTypeImportAssist(ktFile, factoryName, startOffset, endOffset)`
- `buildImportEdit(uri, ktFile, document, fqn)`
- `findImportCandidates(ktFile, shortName)`
- `computeImportInsertion(ktFile, document, fqn)`

`TypeImportAssistPlan` stays with `planTypeImportAssist()` in the shared owner. This avoids a
private code-actions type leaking into diagnostics auto-import.

### Unit Test Helpers

| Method | Signature | Lines | KaSession Extension |
|--------|-----------|-------|---------------------|
| `findUnitTestActionClass` | `private fun findUnitTestActionClass(element: PsiElement): KtClass?` | 1965-1980 | No |
| `buildUnitTestCommand` | `private fun buildUnitTestCommand(plan: KotlinUnitTestPlan): JsonObject` | 1982-2024 | No |
| `buildOpenTargetCommand` | `private fun buildOpenTargetCommand(target: NavigationTarget): JsonObject` | 2026-2042 | No |
| `TestTargetSelection.toJson` | `private fun TestTargetSelection.toJson(): JsonObject` | 2044-2051 | No |

### Utility Helpers

| Method | Signature | Lines | KaSession Extension |
|--------|-----------|-------|---------------------|
| `analyzeBodyReferences` | `private fun KaSession.analyzeBodyReferences(declaration: KtDeclaration, containingClass: KtClass): BodyAnalysis` | 2297-2356 | Yes |
| `cleanModifiersForTopLevel` | `private fun cleanModifiersForTopLevel(function: KtNamedFunction): String` | 2603-2612 | No |
| `indentAt` | `private fun indentAt(document: Document, offset: Int): String` | 2591-2597 | No |
| `lineNeedsBlankLineBeforeDeclarations` | `private fun lineNeedsBlankLineBeforeDeclarations(document: Document, line: Int): Boolean` | 2533-2543 | No |
| `findImportInsertLine` | `private fun findImportInsertLine(ktFile: KtFile, document: Document): Int` | 5150-5155 | No |

## Data Classes to Move

| Data Class | Lines | Used By |
|------------|-------|---------|
| `BodyAnalysis` | 2269 | `analyzeBodyReferences`, `addMoveToCompanionAction`, `addMoveToTopLevelAction`, `addConvertToExtensionFunctionAction` |

### Data Classes Owned by Other Tickets

| Data Class | Owner | Reason |
|------------|-------|--------|
| `NavigationTarget` | `ka-wvv8` / `BridgeNavigationHelpers.kt` | Shared by interface navigation code actions and `implementation()` |
| `TypeImportAssistPlan` | `ka-e6eo` / `BridgeContext` | Shared by diagnostics auto-import and code action import assists |

### Shared Data Classes

| Data Class | Lines | Reason |
|------------|-------|--------|
| `ImportCandidate` | 2270 | Owned by `ka-e6eo` / `BridgeContext`; used by shared `findImportCandidates()` |
| `TypeImportAssistPlan` | 2271-2274 | Owned by `ka-e6eo` / `BridgeContext`; used by shared `planTypeImportAssist()` |

Completion-only types (`ScopeCompletionCandidate`, `RankedScopeCompletionCandidate`, and
`ScopeCompletionSource`) are owned by `ka-zbgz` / `BridgeCompletion.kt`, not by `BridgeContext` or
`BridgeCodeActions.kt`.

## Dependencies on BridgeContext

Code actions methods need these BridgeContext members:

### State Fields
- `session: StandaloneAnalysisAPISession?` — checked for null in `codeActions()`
- `symbolIndex: SymbolIndex` — used by `findImportCandidates()` (via BridgeContext)

### Utility Methods (stay in BridgeContext)
- `findKtFile(session, uri): KtFile?` — resolves URI to KtFile
- `lineColToOffset(ktFile, line, character): Int?` — converts position to offset
- `offsetToLineCol(document, offset): Pair<Int, Int>` — shared document offset conversion
- `uriToPath(uri): String` — strips `file://` prefix
- `selectionForOffset(document, offset, length): JsonObject` — used indirectly through `BridgeNavigationHelpers.kt`
- `fileUriForKtFile(ktFile): String?` — used indirectly through `BridgeNavigationHelpers.kt`
- `allSessionKtFiles(): List<KtFile>` — used indirectly through `BridgeNavigationHelpers.kt`
- `findEnclosingDeclaration(element): KtDeclaration?` — shared PSI lookup for suppress-warning actions
- `planTypeImportAssist(ktFile, factoryName, startOffset, endOffset): TypeImportAssistPlan?` — shared import assist
- `findImportCandidates(ktFile, shortName): List<ImportCandidate>` — shared with completion
- `buildImportEdit(uri, ktFile, document, fqn): JsonObject?` — shared with completion/auto-import
- `computeImportInsertion(ktFile, document, fqn): Pair<Int, String>` — shared with auto-import

### External Types
- `KotlinUnitTestPlanner` / `KotlinUnitTestPlan` / `TestTargetSelection` — separate files
- `AnalyzerCommands` — separate file with command/argument constants

## KaSession Extension Pattern

Six methods are `KaSession.methodName()` extensions. Inside `codeActions()`, they are called within an `analyze(ktFile) { ... }` block where `this` is `KaSession`. In BridgeCodeActions.kt, this pattern is preserved naturally:

```kotlin
// BridgeCodeActions.kt
internal fun BridgeContext.codeActions(uri: String, line: Int, character: Int): JsonObject {
    // ...
    val ktFile = findKtFile(currentSession, uri) ?: ...

    analyze(ktFile) {
        // 'this' is KaSession — extension methods callable directly
        addInterfaceNavigationActions(element, actionsArray)
        addExplicitTypeAction(element, document, fileUri, actionsArray)
        addMoveToCompanionAction(element, document, fileUri, actionsArray)
        addMoveToTopLevelAction(element, document, fileUri, actionsArray, ktFile)
        addConvertToExtensionFunctionAction(element, document, fileUri, actionsArray)
    }
}

// KaSession extension methods become nested: BridgeContext + KaSession
private fun BridgeContext.KaSessionScope.addInterfaceNavigationActions(...) { ... }
```

However, Kotlin does not support chained extension receivers (`BridgeContext.KaSession.method()`). The practical pattern is:

```kotlin
// Option A: KaSession extensions stay as-is (private top-level in the file)
// They only need BridgeContext utilities passed as parameters

private fun KaSession.addInterfaceNavigationActions(
    ctx: BridgeContext,  // added parameter for BridgeContext access
    element: PsiElement,
    actionsArray: JsonArray,
) { ... }

// Option B: Context receiver or closure capture (preferred — cleaner)
// The KaSession extensions are defined inside the analyze block or as
// private functions that receive BridgeContext implicitly via closure
```

**Recommended approach (Option B variant):** Keep KaSession extension methods as `private` top-level functions in BridgeCodeActions.kt. They receive BridgeContext utilities they need (like `fileUriForKtFile`, `selectionForOffset`, `allSessionKtFiles`) through the BridgeContext parameter:

```kotlin
// In BridgeCodeActions.kt

internal fun BridgeContext.codeActions(uri: String, line: Int, character: Int): JsonObject {
    // ...
    analyze(ktFile) {
        addInterfaceNavigationActions(element, actionsArray)  // KaSession.method()
    }
}

// KaSession extension — accesses BridgeContext via explicit parameter
private fun KaSession.addInterfaceNavigationActions(
    ctx: BridgeContext,
    element: PsiElement,
    actionsArray: JsonArray,
) {
    // Can call ctx.fileUriForKtFile(), ctx.selectionForOffset(), etc.
    // Can call ctx.allSessionKtFiles() for implementation search
}
```

Wait -- looking more carefully, these KaSession extensions currently access CompilerBridge members via implicit `this@CompilerBridge`. After extraction, they need those via `BridgeContext`. The cleanest approach: make the KaSession extensions take a `BridgeContext` parameter.

Actually, a simpler pattern matches the epic design: make `codeActions` a `BridgeContext` extension function. The KaSession extension methods also become `BridgeContext` extension functions that are additionally `KaSession` extensions. Since Kotlin doesn't support dual receivers directly, the pattern is:

```kotlin
internal fun BridgeContext.codeActions(uri: String, line: Int, character: Int): JsonObject {
    // 'this' is BridgeContext
    analyze(ktFile) {
        // 'this' is KaSession, BridgeContext accessible via labeled this
        this@codeActions.addInterfaceNavigationActionsImpl(this, element, actionsArray)
    }
}

// Or more simply: pass BridgeContext explicitly to KaSession methods
private fun KaSession.addInterfaceNavigationActions(
    bridge: BridgeContext,
    element: PsiElement,
    actionsArray: JsonArray,
) { ... }
```

**Final recommendation:** Follow the simplest pattern that works. The `codeActions` method is a `BridgeContext` extension. Inside the `analyze` block, call KaSession extension methods that take no BridgeContext parameter when they don't need BridgeContext members, or pass `this@codeActions` (the BridgeContext) when they do.

Methods needing BridgeContext access inside KaSession scope:
- `addInterfaceNavigationActions` — passes `BridgeContext` to shared helpers from `BridgeNavigationHelpers.kt`
- `addExplicitTypeAction` — (no BridgeContext needed)
- `addMoveToCompanionAction` — (no BridgeContext needed, `analyzeBodyReferences` is also KaSession)
- `addMoveToTopLevelAction` — (no BridgeContext needed)
- `addConvertToExtensionFunctionAction` — (no BridgeContext needed)
- `analyzeBodyReferences` — (no BridgeContext needed)
- Shared navigation helpers needing `fileUriForKtFile`, `selectionForOffset`, or `allSessionKtFiles` are not defined here; see `ka-wvv8`.

## Implementation Steps

### Step 1: Create BridgeCodeActions.kt

Create `sidecar/src/main/kotlin/dev/kouros/sidecar/BridgeCodeActions.kt` with:
- Package declaration: `package dev.kouros.sidecar`
- All necessary imports (Kotlin Analysis API, PSI, Gson, etc.)

### Step 2: Move Code-Action Data Classes

Move these to the top of BridgeCodeActions.kt:
1. `BodyAnalysis` (2269) — keep `private` or make `internal` within file

Do not move `NavigationTarget` or `TypeImportAssistPlan` here. `NavigationTarget` belongs to
`BridgeNavigationHelpers.kt`; `TypeImportAssistPlan` belongs with the shared import assist helpers.

### Step 3: Move Utility Helpers (no dependencies on other moving methods)

Move in this order (leaf methods first):
1. `makeEdit` (2361-2379)
2. `makeDeleteEdit` (2560-2573) — depends on `BridgeContext.offsetToLineCol`, `makeEdit`
3. `makeAppendToFileEdit` (2578-2586) — depends on `BridgeContext.offsetToLineCol`, `makeEdit`
4. `indentAt` (2591-2597)
5. `cleanModifiersForTopLevel` (2603-2612)
6. `lineNeedsBlankLineBeforeDeclarations` (2533-2543)
7. `findImportInsertLine` (5150-5155) — depends on BridgeContext.`computeImportInsertion`
8. Do not move `offsetToLineCol` or `findEnclosingDeclaration`; both are owned by `BridgeContext` from `ka-e6eo`.
9. Do not move `isConcreteImplementationCandidate` here; it belongs to `BridgeNavigationHelpers.kt`.

Convert from `private fun` to `private fun BridgeContext.` where BridgeContext access is needed, otherwise keep as `private` top-level or file-scoped functions.

### Step 4: Move KaSession Helpers

1. `analyzeBodyReferences` (2297-2356) — `KaSession` extension, no BridgeContext needed
2. Do not move `navigationTargetFor`, `findImplementationNavigationTargets`, or `findInterfaceNavigationTargets`; they belong to `BridgeNavigationHelpers.kt`.
3. Do not move `planTypeImportAssist`; call the shared `BridgeContext.planTypeImportAssist(...)`.

### Step 5: Move Unit Test Helpers

1. `findUnitTestActionClass` (1965-1980)
2. `TestTargetSelection.toJson` (2044-2051)
3. `buildUnitTestCommand` (1982-2024)
4. `buildOpenTargetCommand` (2026-2042)

### Step 6: Move Action Builders

1. `addOrganizeImportsAction` (2217-2264) — no BridgeContext needed
2. `addConvertBodyAction` (2107-2212) — no BridgeContext needed
3. `addExplicitTypeAction` (2056-2102) — KaSession extension, no BridgeContext needed
4. `addTypeImportCodeActions` (2381-2404) — needs `BridgeContext.buildImportEdit`
5. `addOpenOrCreateUnitTestAction` (1943-1963) — needs `BridgeContext.uriToPath`
6. `addInterfaceNavigationActions` (1827-1868) — KaSession extension
7. `addMoveToCompanionAction` (2619-2689) — KaSession extension
8. `addMoveFromCompanionAction` (2696-2768) — no KaSession needed
9. `addMoveToTopLevelAction` (2776-2889) — KaSession extension
10. `addConvertToExtensionFunctionAction` (2897-2947) — KaSession extension

### Step 7: Move the Public Entry Point

Move `codeActions` (1678-1819) last. Convert to:
```kotlin
internal fun BridgeContext.codeActions(uri: String, line: Int, character: Int): JsonObject
```

### Step 8: Wire Up in CompilerBridge Facade

In CompilerBridge.kt (which will become the facade after ka-e6eo), add a delegation call:
```kotlin
fun codeActions(uri: String, line: Int, character: Int): JsonObject =
    ctx.codeActions(uri, line, character)
```

### Step 9: Update Imports and Verify

- Remove moved methods from CompilerBridge.kt
- Ensure BridgeCodeActions.kt has all required imports
- Run `cd sidecar && ./gradlew test`
- Run `cargo test`

## Visibility Changes

| Original Visibility | New Visibility | Applies To |
|---------------------|----------------|------------|
| `private fun` (member) | `internal fun BridgeContext.` | `codeActions` (public entry point) |
| `private fun` (member) | `private fun BridgeContext.` | Methods needing BridgeContext fields |
| `private fun` (member) | `private fun` (top-level in file) | Pure utility methods needing no state |
| `private fun KaSession.` (member) | `private fun KaSession.` (top-level in file) | KaSession extensions not needing BridgeContext |
| `private data class` | `private` (file-scoped) | `BodyAnalysis` |
| `private data class` | `internal data class` in shared owner | `NavigationTarget` in `BridgeNavigationHelpers.kt`; `TypeImportAssistPlan` with shared import helpers |

## BridgeContext Members That Must Be Internal

For BridgeCodeActions.kt to access these, they must be `internal` (not `private`) on BridgeContext:
- `session`
- `findKtFile()`
- `lineColToOffset()`
- `offsetToLineCol()`
- `selectionForOffset()`
- `fileUriForKtFile()`
- `uriToPath()`
- `allSessionKtFiles()`
- `findEnclosingDeclaration()`
- `planTypeImportAssist()`
- `findImportCandidates()`
- `buildImportEdit()`
- `computeImportInsertion()`

This aligns with the ka-e6eo design where BridgeContext exposes these as `internal` for all feature extension files.

## Acceptance Criteria

- [ ] BridgeCodeActions.kt exists at `sidecar/src/main/kotlin/dev/kouros/sidecar/BridgeCodeActions.kt`
- [ ] Contains ~1,276 lines (the public method + 11 action builders + all helpers)
- [ ] CompilerBridge.kt no longer contains any code action methods
- [ ] `codeActions()` is an `internal fun BridgeContext.codeActions(...)` extension
- [ ] All existing tests pass: `cd sidecar && ./gradlew test` and `cargo test`
- [ ] AnalysisServer.kt requires zero changes
- [ ] No circular dependencies between BridgeCodeActions.kt and other feature files
