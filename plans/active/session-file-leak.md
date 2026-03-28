# Bug: Session File Declarations Leak Into Virtual File Analysis

## Problem

After a code action edit (organize imports, add import, rename, any refactoring), the class defined in the edited file gets a **REDECLARATION** error. The error appears because the Kotlin Analysis API sees both the stale session file (on-disk content) and a fresh LightVirtualFile (edited content) simultaneously. Every declaration that exists in both copies — classes, functions, properties — is visible twice in the module scope.

The same root cause also produces **phantom resolution**: references to names that were deleted or renamed in the edit still resolve through the stale session copy, hiding real `UNRESOLVED_REFERENCE` errors the user should see.

## Reproduction

1. Open a Kotlin project in Zed with kotlin-analyzer active
2. Open a file that has unsorted or duplicate imports
3. Trigger "Organize imports" code action
4. The imports are sorted correctly, but a `REDECLARATION` error appears on the class

The bug affects **all edits to on-disk files**, not just organize imports. Any `textDocument/didChange` that makes the buffer content differ from what the session loaded from disk triggers the leak.

## Root Cause

### The dual-file problem in `findKtFile`

**File**: `sidecar/src/main/kotlin/dev/kouros/sidecar/CompilerBridge.kt`, `findKtFile()` (line 4676)

When a file is edited:

1. Zed applies the edit and sends `textDocument/didChange`
2. The Rust LSP calls `updateFile(uri, newContent)` on the sidecar
3. `updateFile` stores the new content in `virtualFiles[uri]` (line 271)
4. On the next analysis, `findKtFile` is called:
   - It finds the **session file** (loaded from disk at init) at line 4698
   - It compares `sessionFile.text == virtualContent` (line 4703) — **false**, content diverged
   - It falls through and creates a **LightVirtualFile** with the new content (line 4721)
5. The custom `KotlinProjectStructureProvider` (line 4467) maps the LightVirtualFile to the **same source module** as the session file
6. FIR now sees **both files** in the module: the stale session copy and the fresh LightVirtualFile

```
Source Module
├── session file (on-disk content: class Foo, fun main)    ← stale
├── LightVirtualFile (edited content: class Foo, fun main) ← current
├── OtherFile.kt
└── ...
```

Both copies declare the same names in the same package → REDECLARATION.

### Why it was masked before the URI fix

Before the URI fix (commit where `fileUri` was changed from `ktFile.virtualFile?.path` to `uri`), code action edits targeted a temp-dir path like `file:///tmp/kotlin-analyzer-virtual/com/example/File.kt`. Zed couldn't match this to any open buffer, so it opened a new buffer instead of modifying the original. Since the original buffer was unchanged, no `didChange` was sent, `virtualFiles[uri]` was never updated, and `findKtFile` always matched the session file content. The leak existed but was unreachable.

### For on-disk files, `updateFile` never marks the session dirty

```kotlin
// writeVirtualFileToDisk skips files that exist on disk (line 4528)
if (File(filePath).exists()) return

// So sessionDirty is never set for on-disk files (lines 279-286)
// The session is never rebuilt, and the stale session file persists
```

## Evidence

### Failing test

`RedeclarationPropertyTest.sessionDeclarationsMustNotLeakIntoVirtualFile` (in `sidecar/src/test/.../proptest/`)

This test pushes content to Clean.kt's URI that references `Greeter` (a class declared only in the stale session file, not in the pushed content). The test asserts `UNRESOLVED_REFERENCE` should be reported for `Greeter`. It **fails** because FIR resolves `Greeter` through the stale session file — proving the leak.

### What the leak enables

| Symptom | Mechanism |
|---------|-----------|
| REDECLARATION on the class | Both copies declare the same class in the same package |
| Phantom name resolution | Deleted/renamed names still resolve through the stale copy |
| Stale UNRESOLVED_REFERENCE suppression | Names from the stale file satisfy the `declaredNames` filter (line 391), suppressing real errors |

## Fix Options

### Option A: Update the session file's document in-place

When `findKtFile` finds a session file whose content doesn't match `virtualFiles[uri]`, update its backing `Document` to match the virtual content and return the session file. This avoids creating a second file entirely.

```kotlin
if (sessionFile != null && sessionFile.text != virtualContent) {
    val doc = PsiDocumentManager.getInstance(session.project).getDocument(sessionFile)
    if (doc != null) {
        WriteAction.run<RuntimeException> {
            doc.setText(virtualContent)
            PsiDocumentManager.getInstance(session.project).commitDocument(doc)
        }
        return sessionFile
    }
}
```

**Pros**: Single file, no module scope pollution, session stays valid.
**Cons**: Mutating the session file's document may have side effects in FIR caches. Needs investigation into whether `commitDocument` triggers the right invalidation.

### Option B: Remove the session file before creating the LightVirtualFile

Unregister the stale session file from `modulesWithFiles` so FIR only sees the LightVirtualFile.

**Pros**: Clean separation — old file is gone, new file takes over.
**Cons**: The standalone Analysis API doesn't expose a public API to remove individual files from a module. Would require reflection or session rebuild.

### Option C: Rebuild the session with updated content

Mark `sessionDirty = true` for on-disk files too, and before rebuilding, write the virtual content to a temp overlay that takes precedence over the on-disk file.

**Pros**: Clean session state, no dual files.
**Cons**: Session rebuilds are expensive (~100-500ms). Every keystroke would trigger a rebuild. Not viable without incremental session updates.

### Option D: Filter REDECLARATION diagnostics for virtual files

Add `REDECLARATION` to the existing diagnostic filter alongside `UNRESOLVED_REFERENCE` (line 391).

**Pros**: Minimal change, fixes the visible symptom.
**Cons**: Band-aid. Masks real REDECLARATION errors. Does not fix phantom resolution. Does not fix stale `declaredNames` filtering.

### Recommendation

**Option A was tried first** but doesn't work in the standalone Analysis API:
- `Document.setText` + `commitDocument` updates the document but PSI is never re-parsed
- Session files backed by `CoreLocalVirtualFile` are read-only (PSI modification throws `IncorrectOperationException`)
- `CoreLocalVirtualFile.setWritable()` throws "Not supported"
- Swapping files in `session.modulesWithFiles` (a mutable `ArrayList`) succeeds at the data structure level, but FIR's declaration index is baked in at session creation time and doesn't update

**The implemented fix uses a shadow source tree approach:**

When `updateFile()` detects an on-disk file edit, it marks `sessionDirty = true`. On the next `analyze()` call, `ensureSessionCurrent()` triggers a session rebuild. Before rebuilding, `buildShadowSourceRoots()` copies all source files into a temp directory, replacing modified files with their virtual content. The session is then built using ONLY the shadow directory as its source root, ensuring FIR sees exactly one version of each file.

Key changes:
1. `updateFile()` detects on-disk file edits and sets `sessionDirty = true`
2. `buildShadowSourceRoots()` creates a shadow source tree with virtual overrides applied
3. `initialize()` uses shadow roots instead of original source roots when dirty on-disk files exist
4. `findKtFile()` and `findKtFileInSession()` use `shadowPathMapping` to match shadow paths back to original paths

## Status: FIXED

## Verification

1. `sessionDeclarationsMustNotLeakIntoVirtualFile` property test — **PASSES**
2. `editingOnDiskFileNeverCausesRedeclaration` property test — **PASSES**
3. `./gradlew test` — all 108 sidecar tests pass
4. `cargo test` — all Rust LSP tests pass
5. Manual: open a Kotlin project in `my-zed`, trigger organize imports, confirm no REDECLARATION error appears on the class — **TODO**

## Affected Code

| File | Lines | Description |
|------|-------|-------------|
| `sidecar/.../CompilerBridge.kt` | ~62-70 | New fields: `dirtyOnDiskFiles`, `shadowPathMapping` |
| `sidecar/.../CompilerBridge.kt` | ~278-282 | `updateFile` — detects on-disk file edits, sets `sessionDirty` |
| `sidecar/.../CompilerBridge.kt` | ~139-152 | `initialize` — uses shadow source roots when dirty on-disk files exist |
| `sidecar/.../CompilerBridge.kt` | ~4545-4595 | `buildShadowSourceRoots` — creates shadow source tree with virtual overrides |
| `sidecar/.../CompilerBridge.kt` | ~4525-4535 | `findKtFileInSession` — shadow path matching via `shadowPathMapping` |
| `sidecar/.../CompilerBridge.kt` | ~4766-4791 | `findKtFile` — `matchesPath` helper uses `shadowPathMapping` |
| `sidecar/.../CompilerBridge.kt` | ~329-340 | `removeFile` — clears `dirtyOnDiskFiles` entry |
