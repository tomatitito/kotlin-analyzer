# Phase 4: Observability + Polish

**Status: COMPLETED**

## Problem Statement

1. No systematic performance timing across operations
2. No "Organize imports" code action
3. NullPointerException on `virtualFile.path` for KtPsiFactory-created files

## Changes Made

### `sidecar/src/main/kotlin/dev/kouros/sidecar/CompilerBridge.kt`

1. **Added `addOrganizeImportsAction()`** — New code action (kind: `source.organizeImports`) that:
   - Extracts all import paths from the file
   - Deduplicates and sorts them alphabetically
   - Replaces the entire import block with the organized version
   - No-ops when imports are already organized

2. **Added performance timing to 6 methods** — Each logs `[PERF] method=NAME uri=... elapsed=Xms`:
   - `hover()`, `completion()`, `definition()`, `references()`, `codeActions()`, `workspaceSymbols()`

3. **Fixed `fileUri` null safety** — All 3 occurrences of `"file://${ktFile.virtualFile.path}"` replaced with `ktFile.virtualFile?.path?.let { "file://$it" } ?: uri` to handle KtPsiFactory files (which have null virtualFile).

### New Test

- `codeActions - organize imports sorts and deduplicates` — Virtual file with duplicate and unsorted imports, verifies organized output has 3 unique sorted imports.

## Bug Fix: virtualFile.path NPE

During testing, discovered that `ktFile.virtualFile.path` throws NPE for files created via `KtPsiFactory` (used when editor content differs from disk). This affected `codeActions()`, `rename()`, and `semanticTokens()`. Fixed all 3 occurrences to fall back to the `uri` parameter.

## Test Results

- **Sidecar:** 25 tests, 0 failures (was 24)
- **Rust server:** `cargo check` passes
- **Total verified:** 68 tests

## Outcome

- [x] "Organize imports" code action deduplicates and sorts imports
- [x] All 6 major operations log performance timing
- [x] virtualFile.path NPE fixed for virtual files
- [x] All tests pass
