# Language Server Performance Research

This note records the upstream performance model behind the `ka-36lk` performance work. It compares JetBrains Kotlin LSP and rust-analyzer, then states the near-term direction for kotlin-analyzer.

## Current kotlin-analyzer Bottleneck

`ka-kyvv` removed the unconditional full session rebuild from the normal completion path, but the sidecar still has one broad mutable freshness flag:

- `BridgeContext.sessionDirty` tracks missing/failed sessions, ordinary editor-text changes, shadow-root rebuild requirements, new virtual files, deleted files, and diagnostics freshness.
- `updateFile` stores current editor text in `virtualFiles`, but ordinary edits to on-disk files can still mark the whole session dirty when the open-buffer text differs from disk.
- `ensureSessionCurrent()` synchronously rebuilds the `StandaloneAnalysisAPISession`.
- Completion now rebuilds only when `newFilesPendingInSession` is set, but diagnostics, navigation, code actions, and Pebble indexing still call `ensureSessionCurrent()` on request paths.
- The sidecar still answers semantic requests from one Analysis API session and a mix of session PSI, shadow-root PSI, and LightVirtualFile-backed PSI.

That means scheduling improved, but the file/session lifecycle is still not explicit enough to guarantee low interactive latency or to rule out stale and duplicate FIR-visible files.

## JetBrains Kotlin LSP

JetBrains Kotlin LSP runs inside IntelliJ platform project infrastructure. Its Kotlin features operate on live IntelliJ VFS/PSI and Analysis API services instead of a standalone shadow source tree.

Observed source pointers are pinned to `Kotlin/kotlin-lsp@1e679ac320fc20bfd75e5a8dd79c39fabba225eb`.

| Area | What Kotlin LSP does | Source |
|------|----------------------|--------|
| Completion context | Wraps completion in `withAnalysisContextAndFileSettings`, reads the current virtual file, finds PSI, computes the document offset, creates an IntelliJ completion process, and calls `performCompletion`. | [`LSCompletionProviderHelper.kt`](https://github.com/Kotlin/kotlin-lsp/blob/1e679ac320fc20bfd75e5a8dd79c39fabba225eb/features-impl/common/src/com/jetbrains/ls/api/features/impl/common/completion/LSCompletionProviderHelper.kt#L124-L191) |
| Completion edit simulation | Copies the physical PSI file with `KtPsiFactory.createFile`, sets `originalFile`, and runs the edit against `KaDanglingFileResolutionMode.IGNORE_SELF`. | [`LSKotlinCompletionProvider.kt`](https://github.com/Kotlin/kotlin-lsp/blob/1e679ac320fc20bfd75e5a8dd79c39fabba225eb/features-impl/kotlin/src/com/jetbrains/ls/api/features/impl/kotlin/completion/LSKotlinCompletionProvider.kt#L30-L41) |
| LL FIR cache storage | Stores LL FIR session cache storage as a semi-immutable entity. Invalidation creates a copied storage, applies invalidation there, then publishes it so concurrent requests can keep using the prior storage safely. | [`LLFirSessionCacheStorageEntity.kt`](https://github.com/Kotlin/kotlin-lsp/blob/1e679ac320fc20bfd75e5a8dd79c39fabba225eb/features-impl/kotlin/src/com/jetbrains/ls/api/features/impl/kotlin/apiImpl/LLFirSessionCacheStorageEntity.kt#L28-L43) |
| File invalidation | Calls Kotlin LSP's `invalidate(fileUrls, project)` on the current LL FIR cache storage and publishes the new storage if the entity still exists. | [`LLFirSessionCacheStorageEntity.kt`](https://github.com/Kotlin/kotlin-lsp/blob/1e679ac320fc20bfd75e5a8dd79c39fabba225eb/features-impl/kotlin/src/com/jetbrains/ls/api/features/impl/kotlin/apiImpl/LLFirSessionCacheStorageEntity.kt#L77-L88) |
| Diagnostics | Gets per-document diagnostics by finding the current virtual file PSI and running `analyze(ktFile)` with Analysis API diagnostic collection. | [`LSKotlinCompilerDiagnosticsProvider.kt`](https://github.com/Kotlin/kotlin-lsp/blob/1e679ac320fc20bfd75e5a8dd79c39fabba225eb/features-impl/kotlin/src/com/jetbrains/ls/api/features/impl/kotlin/diagnostics/compiler/LSKotlinCompilerDiagnosticsProvider.kt#L35-L50) |

The important design lesson is not that kotlin-analyzer can copy this infrastructure directly. Kotlin LSP benefits from IntelliJ's project model, VFS, PSI document commits, and LL FIR service registration. The lesson is that Kotlin tooling treats current editor PSI and FIR cache state as separable: requests can use the current PSI, while FIR cache invalidation is more granular than rebuilding every semantic context for every edit.

Kotlin Analysis API documentation also describes the same dangling-file model: create a `KtPsiFactory` file copy, set `originalFile`, and analyze it with `KaDanglingFileResolutionMode.IGNORE_SELF` or `PREFER_SELF` depending on whether the copy should reuse the original file's semantic results. See [In-memory file analysis](https://kotlin.github.io/analysis-api/in-memory-file-analysis.html).

## rust-analyzer

rust-analyzer owns its compiler-facing database, so its architecture is more comprehensive than kotlin-analyzer can immediately adopt. It still gives useful request scheduling and state-snapshot patterns.

Observed source pointers are pinned to `rust-lang/rust-analyzer@06aba0c8d642a9c637079f19bb5e2ae88f3ec035`.

| Area | What rust-analyzer does | Source |
|------|-------------------------|--------|
| VFS changes | VFS records file changes and exposes them through `take_changes`; those changes are pushed into Salsa and trigger incremental recomputation. | [`crates/vfs/src/lib.rs`](https://github.com/rust-lang/rust-analyzer/blob/06aba0c8d642a9c637079f19bb5e2ae88f3ec035/crates/vfs/src/lib.rs#L1-L25) |
| Change processing | `GlobalState::process_changes()` drains VFS changes, classifies file edits vs created/deleted structure, normalizes text, builds a Salsa change, applies it to the `AnalysisHost`, and requests workspace reload when structure/config changed. | [`global_state.rs`](https://github.com/rust-lang/rust-analyzer/blob/06aba0c8d642a9c637079f19bb5e2ae88f3ec035/crates/rust-analyzer/src/global_state.rs#L333-L442) |
| Immutable request snapshots | `GlobalStateSnapshot` is explicitly the immutable world state for request handling; request dispatch captures a snapshot before running read-only handlers. | [`global_state.rs`](https://github.com/rust-lang/rust-analyzer/blob/06aba0c8d642a9c637079f19bb5e2ae88f3ec035/crates/rust-analyzer/src/global_state.rs#L206-L221), [`dispatch.rs`](https://github.com/rust-lang/rust-analyzer/blob/06aba0c8d642a9c637079f19bb5e2ae88f3ec035/crates/rust-analyzer/src/handlers/dispatch.rs#L72-L100) |
| Latency-sensitive dispatch | Completion, completion resolve, and semantic tokens run on latency-sensitive threads instead of the normal worker lane. | [`main_loop.rs`](https://github.com/rust-lang/rust-analyzer/blob/06aba0c8d642a9c637079f19bb5e2ae88f3ec035/crates/rust-analyzer/src/main_loop.rs#L1305-L1315) |
| Cancellation and retry | VFS changes trigger analysis cancellation while changes are being applied; cancelled retryable requests are requeued if they were not already cancelled by the client. | [`global_state.rs`](https://github.com/rust-lang/rust-analyzer/blob/06aba0c8d642a9c637079f19bb5e2ae88f3ec035/crates/rust-analyzer/src/global_state.rs#L349-L356), [`dispatch.rs`](https://github.com/rust-lang/rust-analyzer/blob/06aba0c8d642a9c637079f19bb5e2ae88f3ec035/crates/rust-analyzer/src/handlers/dispatch.rs#L236-L274), [`main_loop.rs`](https://github.com/rust-lang/rust-analyzer/blob/06aba0c8d642a9c637079f19bb5e2ae88f3ec035/crates/rust-analyzer/src/main_loop.rs#L799-L804) |
| Background diagnostics and flycheck | Native diagnostics are scheduled separately from request handling, and cargo/flycheck diagnostics arrive on a separate channel and are coalesced. | [`main_loop.rs`](https://github.com/rust-lang/rust-analyzer/blob/06aba0c8d642a9c637079f19bb5e2ae88f3ec035/crates/rust-analyzer/src/main_loop.rs#L526-L560), [`main_loop.rs`](https://github.com/rust-lang/rust-analyzer/blob/06aba0c8d642a9c637079f19bb5e2ae88f3ec035/crates/rust-analyzer/src/main_loop.rs#L440-L443) |

The key lesson is the separation of mutable state ingestion from request handling. Edits are collected, applied to an incremental database, and then read-only requests run against snapshots. Cancellation and retry protect latency-sensitive requests from racing content changes.

## Applicability to kotlin-analyzer

Directly applicable now:

- Treat editor text, Analysis API session state, diagnostics freshness, and project model state as separate lifecycle dimensions.
- Keep Rust as the source of truth for editor URI, document text, LSP version, project model, and replay inputs.
- Keep the sidecar as the source of truth for Analysis API sessions, PSI/FIR lookup strategy, temporary/shadow files, and semantic cache state.
- Use in-memory/dangling PSI for completion, hover, and signature help against a stable module context when the file already exists in the current source set.
- Prevent editor-facing responses from leaking shadow/temp/internal paths.
- Keep background diagnostics cancellable or deferrable when newer editor text arrives.
- Reserve synchronous full session rebuilds for paths that are explicitly allowed to block.

Requires spike:

- Whether standalone Analysis API exposes enough public or internal hooks to invalidate a single changed file's FIR/session state without rebuilding the whole `StandaloneAnalysisAPISession`.
- Whether `KaDanglingFileResolutionMode.IGNORE_SELF` or `PREFER_SELF` works in the standalone sidecar for edited source files without disabling PSI validation globally.
- Whether diagnostics can use dangling PSI for current editor text, or whether they must wait for a debounced rebuild/invalidation path to avoid stale or duplicate FIR-visible files.
- Whether semantic features that traverse the whole source set, such as references, hierarchy, rename, and code actions, can safely combine a stable session source set with current in-memory PSI for the focused file.

Long-term inspiration only:

- A rust-analyzer-style Salsa database for Kotlin symbols and type state.
- Full immutable semantic snapshots owned by kotlin-analyzer.
- Fine-grained dependency invalidation independent of Kotlin Analysis API/FIR internals.

These are not the immediate target because kotlin-analyzer does not own the Kotlin compiler database. Kotlin Analysis API and FIR already own semantic caches, and replacing that with a parallel Salsa model would be a much larger compiler-front-end project.

## Selected Near-Term Direction

The near-term design should be Kotlin-LSP-style rather than rust-analyzer-style:

```text
ordinary edit
    -> keep stable project/session context
    -> answer interactive request from current in-memory/dangling PSI
    -> avoid making FIR see duplicate real files
    -> defer/cancel background diagnostics when needed
    -> rebuild session only for structural/project-model changes
```

That implies the next design should replace `sessionDirty: Boolean` with an explicit freshness model. Ordinary edits to existing files should be handled as editor-text overlay changes, not as source-set or project-model changes. New/deleted/renamed source files, source-root changes, classpath changes, compiler flag changes, Kotlin runtime changes, and project reloads are structural changes and may require a rebuilt `StandaloneAnalysisAPISession`.

The open question for `ka-rd04` is whether standalone Analysis API exposes enough FIR/session invalidation to update diagnostics and non-interactive semantic state without rebuilding. If it does not, the fallback strategy is still useful: interactive requests use in-memory/dangling PSI immediately, while diagnostics and source-set-wide features wait for an explicit debounced rebuild policy.

## Follow-up Impact

- `ka-rd02` should use this note as the rationale for the sidecar file/session lifecycle model.
- `ka-rd03` should prototype dangling PSI for completion and compare it with the current LightVirtualFile path.
- `ka-rd04` should spike LL FIR/session invalidation in the standalone Analysis API runtime.
- `ka-rd05` should implement an explicit rebuild/background-work policy after `ka-rd02` decides the state model.
- `ka-rd06` should add stale and duplicate PSI/FIR visibility regression coverage before the lifecycle refactor is considered complete.
