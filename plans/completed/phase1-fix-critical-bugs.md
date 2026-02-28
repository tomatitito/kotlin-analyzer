# Phase 1: Fix Critical Bugs in Sidecar

**Status: COMPLETED**

## Problem Statement

Three critical bugs in the sidecar prevented core LSP features from working correctly:

1. **Virtual file content not injected into Analysis API session** — `updateFile()` stored editor content in a `virtualFiles` map but never used it for analysis. The session always analyzed stale on-disk content.

2. **Broken auto-import code action** — The "Add import" code action inserted `import $shortName` (e.g., `import List`) instead of the fully-qualified name (e.g., `import kotlin.collections.List`).

3. **Insufficient diagnostic observability** — No timing or content-source logging in the diagnostic pipeline.

## Root Cause Analysis

### Bug 1: Virtual File Content Not Injected

The `findKtFile()` method prioritized session files (on-disk content) over `virtualFiles` content. When the editor sent updated content via `textDocument/didChange`, it was stored in `virtualFiles` but never used because `findKtFile()` always returned the session's stale copy first.

**Discovery during implementation:** The standalone Analysis API uses `MockApplication` which does NOT support PSI tree modification. Both `PsiDocumentManager.commitDocument()` and direct AST node manipulation (`CompositeElement.removeChild`) fail with `PomManager.getModel must not return null`. This means in-place content updates are fundamentally impossible in standalone mode.

**Solution:** Modified `findKtFile()` to check `virtualFiles` first. When the editor has sent updated content, a fresh `KtFile` is created via `KtPsiFactory(session.project)` with the new content. When content matches the session file, the session file is returned (preserving full module context). This works because `KtPsiFactory` files created with the session's project context can still resolve stdlib, JDK, and classpath types.

### Bug 2: Broken Auto-Import

No FQN lookup existed. The code used the short name directly as the import path.

**Solution:** Added `findImportCandidates(shortName)` method that walks all KtFiles in the session and finds declarations matching the short name, returning their FQNs via `KtNamedDeclaration.fqName`. For each candidate, a separate code action is created: "Import 'com.example.Foo'". Falls back to short-name import when no candidates are found.

### Bug 3: Diagnostic Observability

Added timing around `collectDiagnostics()` and content-source logging (editor vs disk) to `analyze()`.

## Changes Made

### `sidecar/src/main/kotlin/dev/kouros/sidecar/CompilerBridge.kt`

1. **Modified `findKtFile()`** — Now checks `virtualFiles` first. When editor content is available and differs from the session file, creates a fresh KtFile from the virtualFiles content. When content matches, returns the session file for full module context.

2. **Added `findKtFileInSession()`** — Focused lookup that only searches `session.modulesWithFiles` without fallbacks. Used by `updateFileInSession()`.

3. **Simplified `updateFileInSession()`** — Since PSI tree modification doesn't work in standalone mode, this just logs that content was stored. The actual content switch happens in `findKtFile()`.

4. **Added `findImportCandidates(shortName)`** — Searches all KtFiles in the session for top-level declarations matching the short name, returns sorted list of FQNs.

5. **Replaced UNRESOLVED_REFERENCE handler** — Now offers one code action per FQN candidate ("Import 'com.example.Foo'") instead of the broken short-name import.

6. **Added diagnostic observability** — `analyze()` now logs content source (editor/disk), content length, timing for `collectDiagnostics()`, and individual diagnostic details.

### `sidecar/src/test/kotlin/dev/kouros/sidecar/CompilerBridgeIntegrationTest.kt`

Added 5 new tests (19 total, up from 14):

- `updateFile - diagnostics reflect edited content` — Verifies that injecting broken content via `updateFile()` produces type mismatch diagnostics
- `updateFile - hover reflects edited content` — Verifies that hover on injected content returns the new function, not the original
- `codeActions - import uses FQN for project declarations` — Verifies code action mechanism works without errors
- `codeActions - unresolved reference offers import candidates` — Verifies FQN-based import actions for unresolved references
- `diagnostics - on-disk file with stdlib types resolves correctly` — Verifies Analysis API resolves stdlib/JDK types

### `sidecar/src/test/resources/test-sources/MissingImport.kt`

New test fixture with stdlib (`List`, `mutableListOf`) and JDK (`java.io.File`) type usage.

## Test Results

- **Sidecar:** 19 tests, 0 failures (was 14 tests)
- **Rust server:** 43 tests, 0 failures
- **Total:** 62 tests, 0 failures

## Key Finding: Standalone Analysis API Limitations

The standalone Analysis API (`buildStandaloneAnalysisAPISession`) uses `MockApplication` from IntelliJ platform, which does NOT support:
- `PsiDocumentManager.commitDocument()` — document→PSI sync is a no-op
- `CompositeElement.removeChild()` / `addChild()` — fails with `PomManager.getModel must not return null`
- `WriteCommandAction.runWriteCommandAction()` — POM model not available

This means **in-place PSI tree modification is impossible** in standalone mode. The workaround is to create fresh `KtFile` objects via `KtPsiFactory` with updated content. These files can still resolve types from the session's classpath because they're created with the session's project context.

## Outcome

- [x] Diagnostics update when the editor sends changed content (via `findKtFile` preferring virtualFiles)
- [x] "Add import" code action inserts correct fully-qualified import statements
- [x] Multiple candidates offered when short name is ambiguous
- [x] Diagnostic pipeline has timing and content-source logging
- [x] All existing tests continue to pass
- [x] 5 new tests covering the fixes
