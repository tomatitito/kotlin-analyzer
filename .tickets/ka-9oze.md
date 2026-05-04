---
id: ka-9oze
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
# Extract BridgeReferences, Rename, SignatureHelp from CompilerBridge

Move symbol query features to separate files (~389 lines total).

## Methods to Extract

### BridgeReferences.kt (~147 lines)

**Public method:**
- `references(uri: String, line: Int, character: Int): JsonObject` (lines 1289-1429)
  - Finds the declaration at cursor, searches all session files for references, appends Pebble Spring index usages, returns locations array.

**Private helpers (relocate from CompilerBridge):**
- `findTargetDeclaration(element: PsiElement): PsiElement?` (lines 5061-5080) — walks up PSI tree to find a named declaration or resolve a reference. **Shared with rename** (line 1577); must be on BridgeContext or a shared utility.
- `findSpringModelProducerDeclaration(element: PsiElement): PsiElement?` (lines 5082-5094) — resolves `addAttribute`/`addObject` calls to their producer declaration. Also used by `definition()` (line 1048); must be on BridgeContext.
- `staticStringValue(expression: KtExpression?): String?` (lines 5096-5100) — extracts literal string value. Called by `findSpringModelProducerDeclaration`; must stay with it on BridgeContext.
- `resolveSpringProducerElement(function: KtNamedFunction, expression: KtExpression): PsiElement?` (lines 5102-5127) — resolves a Spring model producer variable. Called by `findSpringModelProducerDeclaration`; must stay with it on BridgeContext.

**Note on `countReferencesInFile`:** This helper (lines 5163-5183) is annotated as a code lens helper and called only from `codeLens()` (line 3191). It is NOT used by `references()`. It belongs in BridgeCodeLens, not BridgeReferences.

### BridgeRename.kt (~126 lines)

**Public method:**
- `rename(uri: String, line: Int, character: Int, newName: String): JsonObject` (lines 1552-1670)
  - Finds the target declaration, collects all same-file references, returns text edits with new name.

**Shared helpers (on BridgeContext, not relocated here):**
- `findTargetDeclaration` — shared with references (see above)

**No private helpers unique to rename.** The method is self-contained aside from `findTargetDeclaration`.

### BridgeSignatureHelp.kt (~116 lines)

**Public method:**
- `signatureHelp(uri: String, line: Int, character: Int): JsonObject` (lines 1436-1544)
  - Finds enclosing call expression, resolves callee, builds parameter info with active parameter index.

**Private helpers (relocate from CompilerBridge):**
- `computeActiveParameter(callExpression: KtCallExpression, offset: Int): Int` (lines 5036-5052) — counts commas before cursor in argument list. Used only by `signatureHelp`; moves into BridgeSignatureHelp.

**Shared helpers (on BridgeContext, not relocated here):**
- `extractKDocText(declaration: KtDeclaration): String?` — used by signatureHelp (line 1528), hover (lines 3817, 4011, 4318), and completion. Must remain on BridgeContext.

## BridgeContext Dependencies per Feature

### BridgeReferences
| Dependency | Kind |
|---|---|
| `session` | Field — `StandaloneAnalysisAPISession?` |
| `findKtFile(session, uri)` | Method |
| `lineColToOffset(ktFile, line, character)` | Method |
| `ensurePebbleSpringIndexCurrent()` | Method |
| `pebbleSpringIndex` | Field — `PebbleSpringIndex` |
| `findTargetDeclaration(element)` | Method (shared) |
| `findSpringModelProducerDeclaration(element)` | Method (shared) |

### BridgeRename
| Dependency | Kind |
|---|---|
| `session` | Field — `StandaloneAnalysisAPISession?` |
| `findKtFile(session, uri)` | Method |
| `lineColToOffset(ktFile, line, character)` | Method |
| `findTargetDeclaration(element)` | Method (shared) |

### BridgeSignatureHelp
| Dependency | Kind |
|---|---|
| `session` | Field — `StandaloneAnalysisAPISession?` |
| `findKtFile(session, uri)` | Method |
| `lineColToOffset(ktFile, line, character)` | Method |
| `extractKDocText(declaration)` | Method (shared) |

## Imports Required per File

### BridgeReferences.kt
```kotlin
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.*
```

### BridgeRename.kt
```kotlin
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.*
```

### BridgeSignatureHelp.kt
```kotlin
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.KaSuccessCallInfo
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.types.Variance
```

## Implementation Steps

### Step 1: Create BridgeSignatureHelp.kt

This is the simplest extraction with no shared-helper complications.

1. Create `sidecar/src/main/kotlin/dev/kouros/sidecar/BridgeSignatureHelp.kt`.
2. Add `package dev.kouros.sidecar` and required imports.
3. Define `internal fun BridgeContext.signatureHelp(uri, line, character): JsonObject` — move the body from `CompilerBridge.signatureHelp()`.
4. Move `computeActiveParameter` as a `private fun` inside the file (file-private, not an extension).
5. Replace `extractKDocText(psi)` call with `this.extractKDocText(psi)` (already available on BridgeContext).
6. In CompilerBridge, replace the `signatureHelp` method body with a one-line delegation: `return ctx.signatureHelp(uri, line, character)` (where `ctx` is the BridgeContext instance).

### Step 2: Create BridgeRename.kt

1. Create `sidecar/src/main/kotlin/dev/kouros/sidecar/BridgeRename.kt`.
2. Add `package dev.kouros.sidecar` and required imports.
3. Define `internal fun BridgeContext.rename(uri, line, character, newName): JsonObject` — move the body from `CompilerBridge.rename()`.
4. `findTargetDeclaration` is called inside an `analyze` block. It must be available on BridgeContext (confirmed it will be, since it's also used by definition/references).
5. In CompilerBridge, replace the `rename` method body with delegation to `ctx.rename(uri, line, character, newName)`.

### Step 3: Create BridgeReferences.kt

1. Create `sidecar/src/main/kotlin/dev/kouros/sidecar/BridgeReferences.kt`.
2. Add `package dev.kouros.sidecar` and required imports.
3. Define `internal fun BridgeContext.references(uri, line, character): JsonObject` — move the body from `CompilerBridge.references()`.
4. Verify that `findTargetDeclaration`, `findSpringModelProducerDeclaration`, `ensurePebbleSpringIndexCurrent()`, and `pebbleSpringIndex` are all accessible on BridgeContext. These are shared with definition/hover and must be part of the BridgeContext extraction (ka-e6eo).
5. In CompilerBridge, replace the `references` method body with delegation to `ctx.references(uri, line, character)`.

### Step 4: Remove private helpers from CompilerBridge

1. Delete `computeActiveParameter` from CompilerBridge (moved to BridgeSignatureHelp.kt).
2. `findTargetDeclaration`, `findSpringModelProducerDeclaration`, `staticStringValue`, `resolveSpringProducerElement` — these should already live on BridgeContext after ka-e6eo. If not yet moved by ka-e6eo, they must be moved there (not into BridgeReferences) because they are shared across features.
3. Do NOT move `countReferencesInFile` — it belongs with codeLens (ka-lws3).

### Step 5: Update CompilerBridge facade

1. Ensure CompilerBridge holds an `internal val ctx = BridgeContext()` (established by ka-e6eo).
2. The three public methods become one-line delegations:
   ```kotlin
   fun references(uri: String, line: Int, character: Int) = ctx.references(uri, line, character)
   fun rename(uri: String, line: Int, character: Int, newName: String) = ctx.rename(uri, line, character, newName)
   fun signatureHelp(uri: String, line: Int, character: Int) = ctx.signatureHelp(uri, line, character)
   ```

### Step 6: Verify

```bash
cd sidecar && ./gradlew test    # Kotlin unit + integration tests
cargo test                       # Rust unit tests (bridge protocol unchanged)
```

No changes to `AnalysisServer.kt` — it calls `CompilerBridge` public methods which are unchanged.

## Risks and Notes

- **Shared helpers must land on BridgeContext first.** `findTargetDeclaration` and the Spring model helpers are used by definition, references, and rename. The ka-e6eo extraction must place them on BridgeContext. If ka-e6eo scopes them differently, this plan needs adjustment.
- **`analyze` block scope.** All three methods call `analyze(ktFile) { ... }`. Inside the block, `this` is the analysis session, not BridgeContext. Calls to BridgeContext methods (like `findTargetDeclaration`) inside `analyze` blocks work because Kotlin resolves the extension receiver. However, if `findTargetDeclaration` itself needs the analysis context, it may need to be called as `this@references.findTargetDeclaration(element)` or passed explicitly. Verify during implementation.
- **No cross-file rename.** Current `rename()` only renames within the requesting file's PSI tree. `references()` searches all session files. This asymmetry is existing behavior, not introduced by this extraction.
