---
id: ka-lws3
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
# Extract BridgeSemanticTokens, InlayHints, CodeLens from CompilerBridge

Move editor decoration features to separate files (~454 lines total).

## Methods to Extract

### BridgeSemanticTokens.kt (~167 lines)

Public entry point (extension function on BridgeContext):
- `semanticTokens(uri: String): JsonObject` — lines 3271-3431. Contains inline local definitions (`RawToken` data class, `addToken` local function, `walkPsi` local function). All of these move with the method body.

Private helpers (currently `private fun` on CompilerBridge, become `private fun` in the new file):
- `classifyIdentifierByParent(parent: PsiElement?): Int` — lines 5197-5216
- `classifyReferenceExpression(expr: KtSimpleNameExpression): Int` — lines 5223-5236
- `classifyIdentifierModifiers(parent: PsiElement?): Int` — lines 5242-5265
- `buildLegend(tokenTypes: List<String>, tokenModifiers: List<String>): JsonObject` — lines 5270-5279

Companion object constant:
- `KEYWORD_TOKENS: Set<IElementType>` — lines 6083-6138 (in CompilerBridge companion object)

Decision: **Move `KEYWORD_TOKENS` into BridgeSemanticTokens.kt** as a private file-level `val`. It is only referenced by `semanticTokens()` (line 3382) and has no other consumers. Keeping it in the companion object would create an unnecessary coupling. If a future feature needs it, it can be promoted to BridgeContext at that time.

### BridgeInlayHints.kt (~175 lines)

Public entry point (extension function on BridgeContext):
- `inlayHints(uri: String, startLine: Int, endLine: Int): JsonObject` — lines 2984-3152

This method is fully self-contained. No private helpers outside the method body.

### BridgeCodeLens.kt (~112 lines)

Public entry point (extension function on BridgeContext):
- `codeLens(uri: String): JsonObject` — lines 3159-3263

Private helper that moves with it:
- `countReferencesInFile(ktFile: KtFile, declaration: KtNamedDeclaration): Int` — lines 5163-5183. Only caller is `codeLens()` (line 3191). Becomes a private file-level function in BridgeCodeLens.kt.

## Dependencies on BridgeContext

All three features use the same minimal set of BridgeContext members:

| Member | Used by | Purpose |
|--------|---------|---------|
| `session: StandaloneAnalysisAPISession?` | All three | Early return if no session |
| `findKtFile(session, uri): KtFile?` | All three | Resolve URI to KtFile PSI |

Neither `inlayHints` nor `codeLens` nor `semanticTokens` access any other CompilerBridge fields (`virtualFiles`, `sourceModule`, `symbolIndex`, `shadowPathMapping`, etc.).

The `analyze(ktFile) { ... }` call used by `inlayHints` and `codeLens` is a top-level function from the Kotlin Analysis API (`org.jetbrains.kotlin.analysis.api.analyze`), not a CompilerBridge method — no BridgeContext dependency there. `semanticTokens` does not use `analyze` at all (it works at the PSI level only).

## Imports Required per File

### BridgeSemanticTokens.kt
```
com.google.gson.JsonArray, JsonObject
com.intellij.psi.PsiComment, PsiElement, PsiWhiteSpace
org.jetbrains.kotlin.lexer.KtTokens
org.jetbrains.kotlin.psi.* (KtStringTemplateExpression, KtConstantExpression, KtAnnotationEntry,
    KtSimpleNameExpression, KtNamedFunction, KtParameter, KtProperty,
    KtClassOrObject, KtTypeReference, KtTypeAlias, KtTypeParameter,
    KtEnumEntry, KtOperationReferenceExpression, KtCallExpression,
    KtDotQualifiedExpression, KtUserType)
```

### BridgeInlayHints.kt
```
com.google.gson.JsonArray, JsonObject
com.intellij.psi.util.PsiTreeUtil
org.jetbrains.kotlin.analysis.api.analyze
org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall, KaSuccessCallInfo
org.jetbrains.kotlin.psi.* (KtProperty, KtLambdaExpression, KtCallExpression)
org.jetbrains.kotlin.types.Variance
```

### BridgeCodeLens.kt
```
com.google.gson.JsonArray, JsonObject
com.intellij.psi.util.PsiTreeUtil
org.jetbrains.kotlin.analysis.api.analyze
org.jetbrains.kotlin.psi.* (KtClassOrObject, KtNamedFunction, KtNamedDeclaration, KtSimpleNameExpression)
```

## Implementation Steps

### Step 1: Create BridgeSemanticTokens.kt

1. Create `sidecar/src/main/kotlin/dev/kouros/sidecar/BridgeSemanticTokens.kt`.
2. Package: `dev.kouros.sidecar`.
3. Add a file-level `private val KEYWORD_TOKENS = setOf(...)` copied from CompilerBridge companion (lines 6083-6138).
4. Add the extension function:
   ```kotlin
   internal fun BridgeContext.semanticTokens(uri: String): JsonObject { ... }
   ```
   Copy the method body from lines 3271-3431. Replace `session` and `findKtFile(...)` references with BridgeContext accessors (exact names depend on ka-e6eo outcome, but expected to match current names).
5. Add private file-level functions:
   - `private fun classifyIdentifierByParent(parent: PsiElement?): Int`
   - `private fun classifyReferenceExpression(expr: KtSimpleNameExpression): Int`
   - `private fun classifyIdentifierModifiers(parent: PsiElement?): Int`
   - `private fun buildLegend(tokenTypes: List<String>, tokenModifiers: List<String>): JsonObject`
6. Remove from CompilerBridge.kt:
   - The `semanticTokens` method (lines 3271-3431)
   - The four private helper methods (lines 5197-5279)
   - The `KEYWORD_TOKENS` val from companion object (lines 6083-6138)

### Step 2: Create BridgeInlayHints.kt

1. Create `sidecar/src/main/kotlin/dev/kouros/sidecar/BridgeInlayHints.kt`.
2. Package: `dev.kouros.sidecar`.
3. Add the extension function:
   ```kotlin
   internal fun BridgeContext.inlayHints(uri: String, startLine: Int, endLine: Int): JsonObject { ... }
   ```
   Copy method body from lines 2984-3152.
4. Remove the `inlayHints` method from CompilerBridge.kt.

### Step 3: Create BridgeCodeLens.kt

1. Create `sidecar/src/main/kotlin/dev/kouros/sidecar/BridgeCodeLens.kt`.
2. Package: `dev.kouros.sidecar`.
3. Add a private file-level function:
   ```kotlin
   private fun countReferencesInFile(ktFile: KtFile, declaration: KtNamedDeclaration): Int { ... }
   ```
   Copied from lines 5163-5183.
4. Add the extension function:
   ```kotlin
   internal fun BridgeContext.codeLens(uri: String): JsonObject { ... }
   ```
   Copy method body from lines 3159-3263.
5. Remove from CompilerBridge.kt:
   - The `codeLens` method (lines 3159-3263)
   - The `countReferencesInFile` method (lines 5163-5183)

### Step 4: Wire up CompilerBridge facade

Add delegation calls in CompilerBridge.kt (or the facade it becomes after ka-e6eo). The facade methods call through to the BridgeContext extension functions:

```kotlin
fun semanticTokens(uri: String): JsonObject = ctx.semanticTokens(uri)
fun inlayHints(uri: String, startLine: Int, endLine: Int): JsonObject = ctx.inlayHints(uri, startLine, endLine)
fun codeLens(uri: String): JsonObject = ctx.codeLens(uri)
```

Where `ctx` is the BridgeContext instance held by CompilerBridge (established by ka-e6eo).

### Step 5: Clean up removed code

1. Remove any now-unused imports from CompilerBridge.kt (e.g. `PsiComment`, `PsiWhiteSpace` if no other method uses them — verify before removing).
2. Remove the `// --- Private helpers: semantic tokens ---` section header comment from CompilerBridge.kt.

### Step 6: Verify

```bash
cd sidecar && ./gradlew test    # All Kotlin tests pass
cargo test                       # All Rust tests pass
```

## Risks and Notes

- **Depends on ka-e6eo**: This ticket cannot start until BridgeContext exists. The exact accessor names for `session` and `findKtFile` will be determined by that ticket. The plan assumes they are `internal` properties/methods on BridgeContext.
- **Line numbers are approximate**: Other extraction tickets (ka-4dsl through ka-vu27) may shift line numbers if they land first. Use method names and signatures to locate code, not line numbers.
- **No AnalysisServer.kt changes needed**: The public API surface of CompilerBridge is unchanged. AnalysisServer.kt calls `compilerBridge.semanticTokens(uri)` etc. and that continues to work via the facade delegation.
- **KEYWORD_TOKENS ownership**: Moving it out of the companion object is a minor behavioral change (no longer accessible as `CompilerBridge.KEYWORD_TOKENS`). Grep confirms it is only referenced internally from `semanticTokens`, so this is safe.
