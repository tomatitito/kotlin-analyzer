# Investigation: UNRESOLVED_REFERENCE for Member Properties in Virtual Files

## Problem

The Kotlin Analysis API doesn't resolve `this`-scoped member references in virtual files created via `CompilerBridge.updateFile()`. When a class with properties is injected as virtual file content, methods that reference those properties get `UNRESOLVED_REFERENCE` errors.

This valid Kotlin code produces 3 UNRESOLVED_REFERENCE errors for `count`:

```kotlin
class Item {
    var count: Int = 0
    fun increment(): Int {
        count++
        return count
    }
}
```

Same issue with constructor properties:

```kotlin
class Item(var count: Int) {
    fun increment(): Int {
        count++
        return count
    }
}
```

### Observations

- The issue occurs regardless of whether the URI corresponds to a real file on disk or a new virtual file
- Simple function parameters DO resolve correctly (e.g., `fun compute(x: Int): Int { return x + 1 }`)
- Cross-file references between on-disk files work fine (User.kt / UserService.kt)
- Top-level function declarations work fine in virtual files
- The existing integration test `updateFile - diagnostics reflect edited content` does NOT prove that member references resolve — it only checks that errors ARE present (from an intentional type mismatch)

### Production Impact

This isn't just a test issue. Every `updateFile` call from a real editor (on every keystroke that changes a class body referencing properties) produces **false UNRESOLVED_REFERENCE diagnostics**.

## Root Cause Hypothesis

`KtPsiFactory.createFile()` creates a **non-physical** PSI file. The FIR resolution engine resolves top-level declarations but may skip **class-body resolution** for non-physical files because the file isn't in the module's content scope at the FIR session level.

Function parameters resolve because they're in the **function's local scope**. Class properties require **class-scope resolution**, which depends on proper module association at the FIR level.

The custom `KotlinProjectStructureProvider` correctly maps non-physical files to the source module for the Analysis API layer, but this mapping may not be sufficient at the FIR level — the FIR session provider may cache the module's content scope independently.

## Investigation Plan

### Phase 1: Confirm and Narrow Down (~30 min)

- [ ] **1.1 Minimal reproducing test** — add a test to `CompilerBridgeIntegrationTest` that injects the `Item` class via `updateFile` and asserts zero ERROR diagnostics
- [ ] **1.2 Disk vs virtual comparison** — take the exact content of `Clean.kt` (which resolves `name` correctly from disk), inject it via `updateFile` into a different URI, and compare diagnostics
- [ ] **1.3 `this.count` vs bare `count`** — test whether explicit `this.` qualification changes resolution behavior (determines if the issue is implicit `this` receiver resolution or class-scope resolution entirely)

### Phase 2: Examine PSI File Attributes (~15 min)

- [ ] **2.1 Debug logging in `findKtFile`** — print `ktFile.isPhysical`, `ktFile.virtualFile` (null? `LightVirtualFile`?), `ktFile.viewProvider.isPhysical`. Compare attributes between session-managed files and `KtPsiFactory.createFile` files.

### Phase 3: Try Fixes, Simplest First

- [ ] **3.1 `createPhysicalFile()`** (~15 min) — `KtPsiFactory` has this method. If the FIR session keys on `isPhysical`, this single change may fix it.

- [ ] **3.2 `LightVirtualFile` + `PsiManager`** (~30 min) — create a `LightVirtualFile` and resolve the `KtFile` through `PsiManager.findFile()` instead of `KtPsiFactory`. This gives a real VFS-backed file. Update the custom `KotlinProjectStructureProvider` to handle `LightVirtualFile` instances.

- [ ] **3.3 In-place content replacement** (~45 min) — instead of creating new `KtFile` objects, modify the existing session-managed file's content via `document.setText()` and invalidate the PSI cache. Most "correct" approach but may not work with the standalone API's `MockApplication`.

- [ ] **3.4 Deep dive into FIR internals** (~60 min) — only if simpler approaches fail. Trace the FIR resolution phases (RAW_FIR → IMPORTS → SUPER_TYPES → BODY_RESOLVE). Check how IntelliJ's `KtCodeFragment` handles similar cases. Inspect the standalone `StandaloneProjectStructureProvider` for special non-physical file handling.

## Key Files

| File | What to look at |
|------|----------------|
| `CompilerBridge.kt` ~line 3171 | `findKtFile()` — how virtual files are created |
| `CompilerBridge.kt` ~line 3101 | `registerVirtualFileModuleProvider()` — module association |
| `CompilerBridge.kt` ~line 3165 | `updateFileInSession()` — file injection |

## After Fix

Once resolved, restore the `classWithMethodArbitrary()` generator in `KotlinSnippets.kt` to produce classes with methods that reference properties. The `generatedSnippetsCompileCleanly` property test will exercise the fix automatically.

Expand snippet coverage to include:
- Classes with constructor properties referenced in methods
- Classes with body-declared `var`/`val` properties referenced in methods
- Classes with methods that call other methods on `this`
- Classes with property initializers that reference other properties
