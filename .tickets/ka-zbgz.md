---
id: ka-zbgz
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
# Extract BridgeCompletion from CompilerBridge

Move completion(), completionOnce(), and scope/member/import/keyword completion helpers to BridgeCompletion.kt (~376 lines)

## Methods to Extract

### Public entry point

| Method | Lines | Signature |
|--------|-------|-----------|
| `completion` | 776–796 | `fun completion(uri: String, line: Int, character: Int, triggerCharacter: String? = null): JsonObject` |

### Core implementation

| Method | Lines | Signature |
|--------|-------|-----------|
| `completionOnce` | 913–1007 | `private fun completionOnce(uri: String, line: Int, character: Int, triggerCharacter: String?): JsonObject` |

### Dot completion helpers (non-KaSession)

| Method | Lines | Signature |
|--------|-------|-----------|
| `findDotCompletionReceiver` | 4411–4427 | `private fun findDotCompletionReceiver(element: PsiElement, offset: Int): KtExpression?` |
| `findDotCompletionReceiverAtOffset` | 4429–4450 | `private fun findDotCompletionReceiverAtOffset(file: PsiElement, offset: Int): KtExpression?` |
| `findReceiverExpressionEndingAtOffset` | 4452–4466 | `private fun findReceiverExpressionEndingAtOffset(file: KtFile, offset: Int): KtExpression?` |

### KaSession extension methods

| Method | Lines | Signature |
|--------|-------|-----------|
| `collectMemberCompletions` | 4473–4582 | `private fun KaSession.collectMemberCompletions(receiverExpr: KtExpression, itemsArray: JsonArray)` |
| `collectScopeCompletionCandidates` | 4588–4684 | `private fun KaSession.collectScopeCompletionCandidates(ktFile: KtFile, element: PsiElement): List<ScopeCompletionCandidate>` |

### Non-KaSession collection methods

| Method | Lines | Signature |
|--------|-------|-----------|
| `collectImportCompletionItems` | 4692–4809 | `private fun collectImportCompletionItems(importDirective: KtImportDirective, offset: Int): List<JsonObject>` |
| `extractCompletionPrefix` | 4815–4824 | `private fun extractCompletionPrefix(element: PsiElement, offset: Int): String` |
| `collectUnimportedCompletionCandidates` | 4830–4873 | `private fun collectUnimportedCompletionCandidates(ktFile: KtFile, prefix: String, existingLabels: Set<String>): List<ScopeCompletionCandidate>` |
| `collectPsiScopeCompletionCandidates` | 4878–4944 | `private fun collectPsiScopeCompletionCandidates(element: PsiElement): List<ScopeCompletionCandidate>` |
| `keywordCompletionCandidates` | 4949–4974 | `private fun keywordCompletionCandidates(): List<ScopeCompletionCandidate>` |

### Ranking

| Method | Lines | Signature |
|--------|-------|-----------|
| `rankScopeCompletionCandidates` | 4980–5009 | `private fun rankScopeCompletionCandidates(prefix: String, candidates: List<ScopeCompletionCandidate>): List<JsonObject>` |
| `sourcePriority` | 5011–5015 | `private fun sourcePriority(source: ScopeCompletionSource): Int` |
| `matchPriority` | 5017–5028 | `private fun matchPriority(label: String, prefix: String, normalizedPrefix: String): Int` |

### Classification

| Method | Lines | Signature |
|--------|-------|-----------|
| `symbolToCompletionKind` | 5354–5364 | `private fun symbolToCompletionKind(symbol: KaCallableSymbol): String` |
| `psiToCompletionKind` | 5369–5378 | `private fun psiToCompletionKind(declaration: KtNamedDeclaration): String` |

## Data Classes and Enums to Move

| Type | Lines | Definition |
|------|-------|------------|
| `ScopeCompletionCandidate` | 2275–2279 | `private data class ScopeCompletionCandidate(val label: String, val source: ScopeCompletionSource, val item: JsonObject)` |
| `RankedScopeCompletionCandidate` | 2280–2285 | `private data class RankedScopeCompletionCandidate(val candidate: ScopeCompletionCandidate, val sourcePriority: Int, val matchPriority: Int, val originalIndex: Int)` |
| `ScopeCompletionSource` | 2286–2290 | `private enum class ScopeCompletionSource { VISIBLE_SCOPE, KEYWORD, AUTO_IMPORT }` |

## Dependencies on BridgeContext

These methods/fields are used by completion code but belong to BridgeContext (shared with other features):

| Dependency | Used by | Usage |
|------------|---------|-------|
| `session` | `completionOnce` | Get the active analysis session |
| `sessionDirty` | `completion`, `completionOnce` | Check if session needs rebuild |
| `ensureSessionCurrent()` | `completion` | Trigger session rebuild before retry |
| `findKtFile(session, uri)` | `completionOnce` | Resolve URI to KtFile |
| `lineColToOffset(ktFile, line, char)` | `completionOnce` | Convert line/col to PSI offset |
| `classpathFqns` | `collectImportCompletionItems` | FQN index for import completions |
| `symbolIndex` | `collectImportCompletionItems`, `collectUnimportedCompletionCandidates` | Project symbol index |
| `computeImportInsertion(ktFile, doc, fqn)` | `collectUnimportedCompletionCandidates` | Compute import edit position (shared with code actions) |

Note: `analyze(ktFile) { ... }` at line 938 is the Kotlin Analysis API top-level function (`org.jetbrains.kotlin.analysis.api.analyze`), not a CompilerBridge method.

## Implementation Steps

### Step 1: Create BridgeCompletion.kt

Create `sidecar/src/main/kotlin/dev/kouros/sidecar/BridgeCompletion.kt` in package `dev.kouros.sidecar`.

File structure:

```kotlin
package dev.kouros.sidecar

// imports ...

// --- Data types ---

internal data class ScopeCompletionCandidate(...)
private data class RankedScopeCompletionCandidate(...)
internal enum class ScopeCompletionSource { ... }

// --- Public entry point ---

internal fun BridgeContext.completion(...): JsonObject { ... }

// --- Core implementation ---

private fun BridgeContext.completionOnce(...): JsonObject { ... }

// --- Dot completion helpers ---

private fun findDotCompletionReceiver(...): KtExpression? { ... }
private fun findDotCompletionReceiverAtOffset(...): KtExpression? { ... }
private fun findReceiverExpressionEndingAtOffset(...): KtExpression? { ... }

// --- KaSession member/scope collection ---

private fun KaSession.collectMemberCompletions(...) { ... }
private fun BridgeContext.collectScopeCompletionCandidatesInSession(
    ktFile: KtFile, element: PsiElement, session: KaSession
): List<ScopeCompletionCandidate> { ... }

// --- Non-KaSession collection ---

private fun BridgeContext.collectImportCompletionItems(...): List<JsonObject> { ... }
private fun extractCompletionPrefix(...): String { ... }
private fun BridgeContext.collectUnimportedCompletionCandidates(...): List<ScopeCompletionCandidate> { ... }
private fun collectPsiScopeCompletionCandidates(...): List<ScopeCompletionCandidate> { ... }
private fun keywordCompletionCandidates(): List<ScopeCompletionCandidate> { ... }

// --- Ranking ---

private fun rankScopeCompletionCandidates(...): List<JsonObject> { ... }
private fun sourcePriority(...): Int { ... }
private fun matchPriority(...): Int { ... }

// --- Classification ---

private fun symbolToCompletionKind(...): String { ... }
private fun psiToCompletionKind(...): String { ... }
```

### Step 2: Move data classes and enums

Move `ScopeCompletionCandidate`, `RankedScopeCompletionCandidate`, and `ScopeCompletionSource` from CompilerBridge.kt (lines 2275–2290) to BridgeCompletion.kt. Change visibility:

- `ScopeCompletionCandidate` — `internal` (file-private in original; stays package-visible since BridgeCompletion needs it)
- `RankedScopeCompletionCandidate` — `private` (only used within ranking logic)
- `ScopeCompletionSource` — `internal` (referenced by ScopeCompletionCandidate)

### Step 3: Convert methods to extension functions on BridgeContext

Methods that access BridgeContext state become `BridgeContext.` extension functions:

- `completion` — accesses `sessionDirty`, `ensureSessionCurrent()`
- `completionOnce` — accesses `session`, `sessionDirty`, `findKtFile()`, `lineColToOffset()`
- `collectImportCompletionItems` — accesses `classpathFqns`, `symbolIndex`
- `collectUnimportedCompletionCandidates` — accesses `symbolIndex`, `computeImportInsertion()`

Methods that do NOT access BridgeContext state become plain private functions:

- `findDotCompletionReceiver`, `findDotCompletionReceiverAtOffset`, `findReceiverExpressionEndingAtOffset`
- `extractCompletionPrefix`
- `collectPsiScopeCompletionCandidates`
- `keywordCompletionCandidates`
- `rankScopeCompletionCandidates`, `sourcePriority`, `matchPriority`
- `symbolToCompletionKind`, `psiToCompletionKind`

KaSession extension methods (`collectMemberCompletions`, `collectScopeCompletionCandidates`) keep their receiver but the one that accesses BridgeContext (`collectScopeCompletionCandidates` calls `collectPsiScopeCompletionCandidates`) gets refactored: pass context as a parameter or make it a BridgeContext extension that takes a KaSession parameter.

### Step 4: Handle the KaSession dual-receiver problem

`collectScopeCompletionCandidates` is currently `KaSession.collectScopeCompletionCandidates(...)`. It also needs `BridgeContext` (indirectly, through `collectPsiScopeCompletionCandidates` which is a plain function, so no context needed). Similarly, `collectMemberCompletions` is pure KaSession with no BridgeContext access. Both can remain `KaSession` extension functions defined as private top-level functions in BridgeCompletion.kt.

Verify: `collectScopeCompletionCandidates` calls `collectPsiScopeCompletionCandidates` (line 4677, 4680). Since `collectPsiScopeCompletionCandidates` becomes a private file-level function (no BridgeContext needed), this works without a dual receiver.

### Step 5: Update CompilerBridge.kt facade

Replace the extracted methods in CompilerBridge.kt with a single delegation call:

```kotlin
fun completion(uri: String, line: Int, character: Int, triggerCharacter: String? = null): JsonObject =
    ctx.completion(uri, line, character, triggerCharacter)
```

Where `ctx` is the `BridgeContext` instance held by the facade (created by ka-e6eo).

Delete:
- Lines 776–796 (completion body, keep as one-line delegation)
- Lines 913–1007 (completionOnce)
- Lines 2275–2290 (data classes/enums)
- Lines 4411–4466 (dot completion helpers)
- Lines 4473–4684 (member/scope collection)
- Lines 4692–4809 (import completion)
- Lines 4815–4824 (extractCompletionPrefix)
- Lines 4830–4974 (unimported, PSI scope, keyword candidates)
- Lines 4980–5028 (ranking)
- Lines 5354–5378 (symbolToCompletionKind, psiToCompletionKind)

### Step 6: Ensure BridgeContext exposes needed API

BridgeContext (from ka-e6eo) must expose these as `internal`:

- `session: StandaloneAnalysisAPISession?`
- `sessionDirty: Boolean`
- `ensureSessionCurrent()`
- `findKtFile(session, uri): KtFile?`
- `lineColToOffset(ktFile, line, character): Int?`
- `classpathFqns: Set<String>`
- `symbolIndex: SymbolIndex`
- `computeImportInsertion(ktFile, document, fqn): Pair<Int, String>`

### Step 7: Verify

```bash
cd sidecar && ./gradlew test
cargo test
```

All existing tests must pass without modification. AnalysisServer.kt must remain unchanged.

## Notes

- `symbolToCompletionKind` and `psiToCompletionKind` are only used by completion methods (verified via grep). They move entirely to BridgeCompletion.kt.
- `computeImportInsertion` is shared with code actions (lines 2504, 5154) so it stays on BridgeContext.
- The `analyze(ktFile) { }` call is the Kotlin Analysis API's top-level function, not a CompilerBridge method, so no extraction concern.
- Estimated total: ~376 lines (17 methods + 3 data types).
