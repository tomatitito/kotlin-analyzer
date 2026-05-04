---
id: ka-b4j7
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
# Extract BridgeHover from CompilerBridge

Move hover(), hoverOnce(), and KDoc/documentation helpers to BridgeHover.kt (~617 lines).

## Methods to Extract

### Public entry point

| Method | Signature | Lines | Notes |
|--------|-----------|-------|-------|
| `hover` | `fun hover(uri: String, line: Int, character: Int): JsonObject` | 751-770 | Moves to `BridgeHover.kt` as `internal fun BridgeContext.hover(...)`. Owns retry-after-rebuild logic and perf logging. `CompilerBridge.hover(...)` becomes a one-line facade delegating to `ctx.hover(...)`. |

### Core hover logic (moves to BridgeHover.kt)

| Method | Signature | Lines | Notes |
|--------|-----------|-------|-------|
| `hoverOnce` | `private fun hoverOnce(uri: String, line: Int, character: Int): JsonObject` | 798-911 | Moves to `BridgeHover.kt` as `private fun BridgeContext.hoverOnce(...)`. Core implementation. Uses `session`, `findKtFile`, `lineColToOffset`, `findHoverElement` from BridgeContext. Contains `analyze(ktFile) { }` block. |

### KaSession extension methods (moves to BridgeHover.kt)

These are all `org.jetbrains.kotlin.analysis.api.KaSession.` extension functions, called from inside `analyze { }` blocks.

| Method | Signature | Lines | Notes |
|--------|-----------|-------|-------|
| `buildDeclarationHover` | `private fun KaSession.buildDeclarationHover(declaration: KtNamedDeclaration): String?` | 3795-3878 | Renders declaration with container info, annotations, supertypes, KDoc, source origin. |
| `buildReferenceHover` | `private fun KaSession.buildReferenceHover(refExpr: KtReferenceExpression): String?` | 3884-3967 | Resolves reference to target symbol, renders it. Fallback to expression type. |
| `buildAnnotationEntryHover` | `private fun KaSession.buildAnnotationEntryHover(annotationEntry: KtAnnotationEntry): String?` | 3974-4052 | Resolves annotation type reference to annotation class, renders class info. |
| `buildContainerInfo` | `private fun KaSession.buildContainerInfo(symbol: KaDeclarationSymbol): String?` | 4059-4135 | Shows package/containing class context, e.g. `*(org.example)*`. |
| `buildAnnotationsBlock` | `private fun KaSession.buildAnnotationsBlock(symbol: KaDeclarationSymbol): String?` | 4141-4182 | Renders filtered meta-annotations inside code block. Calls `renderAnnotationArgValue`. |
| `buildSupertypesLine` | `private fun KaSession.buildSupertypesLine(symbol: KaDeclarationSymbol): String?` | 4223-4242 | Renders `: Interface1, Interface2`, skips Any/Annotation. |
| `extractSymbolDocumentation` | `private fun KaSession.extractSymbolDocumentation(symbol: KaDeclarationSymbol): String?` | 4311-4337 | Tries KDoc, decompiled stub doc, Javadoc. Calls `extractKDocText`, `extractDocCommentFromPsiText`, `extractJavadocFromPsi`. **Also used by `renderLibrarySymbolStub` in `BridgeNavigation.kt`. Must be `internal` so navigation code can call it.** |

### Non-KaSession helper methods (moves to BridgeHover.kt)

| Method | Signature | Lines | Notes |
|--------|-----------|-------|-------|
| `renderAnnotationArgValue` | `private fun renderAnnotationArgValue(value: KaAnnotationValue): String` | 4188-4217 | Renders enum entries, arrays, constants, class literals, nested annotations. Called by `buildAnnotationsBlock`. |
| `buildSourceOrigin` | `private fun buildSourceOrigin(symbol: KaDeclarationSymbol): String?` | 4247-4282 | Not a KaSession extension. Shows JAR origin for library symbols. |
| `enrichKDocText` | `private fun enrichKDocText(text: String): String` | 4289-4302 | Converts `[ClassName]` refs to inline code, formats Javadoc-style tags. |
| `extractDocCommentFromPsiText` | `private fun extractDocCommentFromPsiText(declaration: KtDeclaration): String?` | 4343-4364 | Walks PSI siblings for doc comments. |
| `parseDocCommentText` | `private fun parseDocCommentText(comment: String): String?` | 4370-4377 | Strips `/**` markers and leading asterisks. |
| `extractJavadocFromPsi` | `private fun extractJavadocFromPsi(element: PsiElement): String?` | 4382-4403 | Reflection-based Javadoc extraction from Java PSI. |

## KaSession Pattern

The `KaSession.` extension methods (`buildDeclarationHover`, `buildReferenceHover`, `buildAnnotationEntryHover`, `buildContainerInfo`, `buildAnnotationsBlock`, `buildSupertypesLine`, `extractSymbolDocumentation`) are declared as extensions on `org.jetbrains.kotlin.analysis.api.KaSession`. They can only be called inside an `analyze(ktFile) { ... }` block, which provides the `KaSession` receiver.

In BridgeHover.kt, these become:

```kotlin
internal fun BridgeContext.hover(uri: String, line: Int, character: Int): JsonObject {
    val result = hoverOnce(uri, line, character)
    // retry-after-rebuild logic lives here
    return result
}

private fun BridgeContext.hoverOnce(uri: String, line: Int, character: Int): JsonObject {
    // ...
    analyze(ktFile) {
        // Inside here, `this` is KaSession
        // Call KaSession extensions directly:
        val hoverText = buildDeclarationHover(declaration)
    }
}

// KaSession extensions are file-private, nested inside the BridgeHover.kt file:
private fun KaSession.buildDeclarationHover(declaration: KtNamedDeclaration): String? {
    // Can call other KaSession extensions from here
    val containerInfo = buildContainerInfo(symbol)
    // Can also call non-KaSession helpers:
    val origin = buildSourceOrigin(symbol)
}
```

The non-KaSession helpers (`renderAnnotationArgValue`, `buildSourceOrigin`, `enrichKDocText`, etc.) become `private` top-level functions or `private` functions inside the file scope. They don't need a `BridgeContext` receiver since they don't access any shared state.

## Dependencies on BridgeContext

Methods from BridgeContext that hover code calls:

| BridgeContext member | Used by | Type |
|---------------------|---------|------|
| `session` | `hoverOnce` | Property (field) |
| `sessionDirty` | `hover` | Property (field) |
| `findKtFile(session, uri)` | `hoverOnce` | Method |
| `lineColToOffset(ktFile, line, character)` | `hoverOnce` | Method |
| `findHoverElement(ktFile, line, offset)` | `hoverOnce` | Method |
| `extractKDocText(declaration)` | `buildDeclarationHover`, `buildAnnotationEntryHover`, `extractSymbolDocumentation` | Method — **shared with CodeLens**, stays in BridgeContext |
| `ensureSessionCurrent()` | `hover` retry logic | Method |

## Shared Methods

### `extractSymbolDocumentation`

This KaSession extension is used by both hover and navigation (`renderLibrarySymbolStub`). It should live in BridgeHover.kt with `internal` visibility so `BridgeNavigation.kt` can import and call it. Since it's a KaSession extension and its primary home is hover, keeping it in BridgeHover.kt with `internal` visibility is cleaner than putting it on `BridgeContext`.

### `extractKDocText`

Used by hover (lines 3817, 4011, 4318), code actions (line 1528), and extractSymbolDocumentation (line 4318). Already designated to stay in BridgeContext.

### `buildContainerInfo`, `buildAnnotationsBlock`, `buildSupertypesLine`

Currently only used by hover methods. If code actions or other features later need them, they can be made `internal`. For now, `private` file-scope in BridgeHover.kt is sufficient.

## Import Statements for BridgeHover.kt

```kotlin
package dev.kouros.sidecar

import com.google.gson.JsonObject
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.types.Variance
```

## Implementation Steps

### Step 1: Create BridgeHover.kt with the extension function skeleton

Create `sidecar/src/main/kotlin/dev/kouros/sidecar/BridgeHover.kt` with:
- Package declaration and imports
- `internal fun BridgeContext.hover(...)` — move CompilerBridge lines 751-770, preserving retry-after-rebuild logic and perf logging with BridgeContext state/methods
- `private fun BridgeContext.hoverOnce(...)` — copy body from CompilerBridge lines 798-911, replacing `session` with `this.session`, `findKtFile` with `this.findKtFile`, etc.
- All KaSession extension methods as `private` file-level functions (lines 3795-4242)
- `internal fun KaSession.extractSymbolDocumentation(...)` (lines 4311-4337) — `internal` because `BridgeNavigation.kt` needs it
- All non-KaSession helpers as `private` file-level functions (lines 4247-4403)

### Step 2: Update `hover()` in CompilerBridge facade

The public `hover()` method in `CompilerBridge.kt` becomes a one-line facade delegation:
```kotlin
fun hover(uri: String, line: Int, character: Int): JsonObject =
    ctx.hover(uri, line, character)
```

The retry-after-rebuild logic moves into `BridgeHover.kt`:
```kotlin
internal fun BridgeContext.hover(uri: String, line: Int, character: Int): JsonObject {
    val perfStart = System.currentTimeMillis()
    var result = hoverOnce(uri, line, character)

    if (!result.has("contents") && sessionDirty) {
        ensureSessionCurrent()
        result = hoverOnce(uri, line, character)
    }

    System.err.println("[PERF] method=hover uri=$uri elapsed=${System.currentTimeMillis() - perfStart}ms")
    return result
}
```

### Step 3: Remove extracted methods from CompilerBridge.kt

Delete the following line ranges from CompilerBridge.kt:
- Lines 751-770 (`hover` body, replaced by one-line delegation)
- Lines 798-911 (`hoverOnce`)
- Lines 3795-4052 (`buildDeclarationHover`, `buildReferenceHover`, `buildAnnotationEntryHover`)
- Lines 4059-4217 (`buildContainerInfo`, `buildAnnotationsBlock`, `renderAnnotationArgValue`)
- Lines 4223-4242 (`buildSupertypesLine`)
- Lines 4247-4403 (`buildSourceOrigin`, `enrichKDocText`, `extractSymbolDocumentation`, `extractDocCommentFromPsiText`, `parseDocCommentText`, `extractJavadocFromPsi`)

### Step 4: Adjust visibility

- `findKtFile`, `lineColToOffset`, `findHoverElement`, `extractKDocText` in BridgeContext must be `internal` (not `private`) so BridgeHover.kt can call them.
- `session` and `sessionDirty` fields in BridgeContext must be `internal`.

### Step 5: Verify

```bash
cd sidecar && ./gradlew test
cargo test
```

All existing tests must pass with zero changes to test files or AnalysisServer.kt.

## Line Count Estimate

| Content | Lines |
|---------|-------|
| hoverOnce | 114 |
| buildDeclarationHover | 84 |
| buildReferenceHover | 84 |
| buildAnnotationEntryHover | 79 |
| buildContainerInfo | 77 |
| buildAnnotationsBlock | 42 |
| buildSupertypesLine | 20 |
| renderAnnotationArgValue | 30 |
| buildSourceOrigin | 36 |
| enrichKDocText | 14 |
| extractSymbolDocumentation | 27 |
| extractDocCommentFromPsiText | 22 |
| parseDocCommentText | 8 |
| extractJavadocFromPsi | 22 |
| Imports + file header | 25 |
| **Total** | **~684** |
