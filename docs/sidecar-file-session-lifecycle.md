# Sidecar File and Session Lifecycle

This design defines how editor text, PSI files, shadow-session files, Analysis API sessions, and FIR-visible source files should move through kotlin-analyzer. It is the implementation contract for `ka-rd03`, `ka-rd04`, `ka-rd05`, and `ka-rd06`.

Inputs:

- [Compiler Integration](compiler-integration.md#psi-fir-and-editor-text-file-models) defines PSI, FIR, in-memory PSI, LightVirtualFile-backed PSI, shadow-root PSI, and dangling PSI.
- [Language Server Performance Research](language-server-performance-research.md) selects the near-term Kotlin-LSP-style direction.
- [Session File Leak](../plans/active/session-file-leak.md) documents the duplicate/stale file failure mode that led to the current shadow-root workaround.

## Current Call-Site Inventory

The current sidecar lifecycle is concentrated in `BridgeContext`:

- `updateFile(uri, text)` stores editor text in `virtualFiles`, marks `sessionDirty` for changed on-disk files and temp virtual files, and re-indexes the file for lightweight symbol lookup.
- `removeFile(uri)` removes editor text and marks `sessionDirty` when dirty on-disk or temp virtual state existed.
- `ensureSessionCurrent()` synchronously rebuilds the `StandaloneAnalysisAPISession` when `sessionDirty` is true.
- `buildShadowSourceRoots(...)` creates a shadow source tree with virtual editor overrides, then `initialize(...)` builds the session from that shadow tree.
- `findKtFile(...)` chooses among session PSI, shadow-root PSI, cached LightVirtualFile-backed PSI, and fallback physical-file PSI.
- `fileUriForKtFile(...)` / `buildFileUri(...)` are the response boundary where internal paths must map back to editor-facing URIs.

Rust already has the right ownership shape: `DocumentStore` stores open document URI, full text, version, and kind; `server` sends `didOpen` / `didChange` to the sidecar; `server` replays open documents after sidecar startup. `bridge` remains the JSON-RPC transport/process layer and must not pull from `state` or `project`.

## Ownership Boundary

Rust server owns:

- editor URI, document text, LSP document version, and document kind;
- project model inputs: project root, source roots, classpath, compiler flags, JDK, Kotlin runtime line;
- sidecar replay orchestration after startup or restart;
- LSP diagnostic publication and version checks before diagnostics reach the editor.

JVM sidecar owns:

- `StandaloneAnalysisAPISession` objects and disposal;
- PSI/FIR lookup strategy for semantic requests;
- editor-text overlays received from Rust;
- shadow roots, LightVirtualFile-backed files, dangling PSI files, and path mapping;
- semantic caches such as symbol/classpath indexes and any future FIR invalidation handles.

`bridge` owns only:

- process lifecycle, JSON-RPC framing, pending request cancellation, and restart attempts;
- stored initialization parameters needed to spawn the JVM;
- no document/project replay decisions.

If a sidecar restart happens below `server`, `bridge` may restart the process and re-send initialize inputs, but `server` must still orchestrate replay of open documents from `DocumentStore`. A follow-up should make this explicit for automatic restarts, not only initial startup.

## Lifecycle Matrix

| Change type | Session reuse | In-memory/dangling PSI allowed | Diagnostics policy | Full rebuild |
|-------------|---------------|--------------------------------|--------------------|--------------|
| Open existing source file | Reuse if the file is already in the current source set. | Yes. Store editor overlay and use current text for focused-file interactive requests. | Schedule diagnostics for that URI; publish only if the version still matches. | No. |
| Ordinary text edit in known source file | Reuse the existing project/session context. | Yes. This is the primary dangling PSI path for completion, hover, and signature help. | Invalidate/defer current URI diagnostics; either run dangling diagnostics if proven safe or wait for debounce/rebuild policy. | No synchronous rebuild. |
| Save known source file | Reuse if source membership and project model did not change. | Yes until Rust confirms sidecar overlay matches saved text. | Re-run diagnostics for saved version; clear overlay-dirty marker when text matches disk/session shadow. | No by default. |
| Close without unsaved changes | Reuse. | Drop editor overlay if it matches disk/session state. | Keep cached diagnostics in Rust for tab switching; sidecar need not analyze. | No. |
| Close with unsaved changes | Reuse. | Drop overlay only after Rust has no open buffer source of truth. | Do not publish diagnostics for closed obsolete version; Rust cache remains editor-facing history. | No. |
| New source file inside known source root | Existing session lacks a FIR-visible source member. | Use in-memory PSI only for syntax/light features until source-set update policy runs; dangling context may be unavailable. | Defer semantic diagnostics until source set is rebuilt or file-level insertion is proven. | Yes, async/debounced source-set rebuild unless `ka-rd04` proves insertion invalidation. |
| Deleted or renamed source file | Existing session has wrong FIR-visible source set. | No for deleted file; renamed target follows new-file rules. | Clear diagnostics for deleted URI; defer affected semantic diagnostics. | Yes, async/debounced source-set rebuild. |
| Source-root, classpath, compiler-flag, Kotlin-version, JDK, or project-model change | Cannot safely reuse for semantic correctness. | Only after a new session exists. | Cancel/defer diagnostics during rebuild; publish empty/stale-safe state if needed. | Yes, project-model rebuild. |
| Sidecar crash/restart/replay | Old session is missing/disposed. | Not until initialize and replay recreate overlays. | Rust should retain cached diagnostics but avoid publishing new sidecar diagnostics until replay completes. | Initialize new session, then replay open overlays from Rust. |

## Required Flow Diagrams

Ordinary edit:

```text
didChange existing Foo.kt
    -> Rust updates document store/version
    -> Rust sends sidecar text update
    -> sidecar updates editor-text overlay
    -> sidecar marks Foo.kt overlay-dirty, not source-set dirty
    -> completion/hover/signature help use current text without full rebuild
    -> diagnostics are scheduled/deferred according to policy
    -> published diagnostics are version-checked before reaching the editor
```

Structural/project-model change:

```text
structural/project-model change
    -> Rust updates project model
    -> Rust sends explicit rebuild input to sidecar
    -> sidecar builds one FIR-visible source set
    -> sidecar rebuilds Analysis API session
    -> semantic requests resume against the new session
```

Sidecar restart:

```text
sidecar restart
    -> server restarts bridge process
    -> server replays project model
    -> server replays open document text/version
    -> sidecar reconstructs Analysis API state and editor overlays
    -> diagnostics and semantic requests resume after replay
```

## Freshness Model Decision

Replace `sessionDirty: Boolean` with a small state model plus per-file overlay metadata. A single boolean is too coarse because ordinary text edits, source-set changes, project-model changes, missing sessions, and diagnostics staleness have different request behavior.

Proposed Kotlin shape:

```kotlin
internal sealed interface SessionFreshness {
    data object Current : SessionFreshness
    data class Missing(val reason: MissingReason) : SessionFreshness
    data class EditorOverlayDirty(val uris: Set<String>) : SessionFreshness
    data class SourceSetDirty(val uris: Set<String>, val reason: SourceSetDirtyReason) : SessionFreshness
    data class ProjectModelDirty(val reason: ProjectModelDirtyReason) : SessionFreshness
}

internal enum class MissingReason { NotInitialized, Disposed, BuildFailed, Restarting }
internal enum class SourceSetDirtyReason { NewFile, DeletedFile, RenamedFile, SourceRootChanged }
internal enum class ProjectModelDirtyReason { ClasspathChanged, CompilerFlagsChanged, KotlinVersionChanged, JdkChanged, ProjectReloaded }
```

Implementation may use orthogonal fields internally if it is cleaner:

- `sessionState: Missing | Available`
- `editorOverlayDirtyUris: Set<String>`
- `sourceSetDirty: DirtyReason?`
- `projectModelDirty: DirtyReason?`
- `diagnosticsDirty: Map<String, VersionedDirtyReason>`

The observable contract is the same: request handlers must distinguish these dimensions and must not treat all dirty states as permission to synchronously rebuild.

## Legal State Transitions

| Setter path | Legal freshness transition |
|-------------|----------------------------|
| `initialize(...)` success | `Missing` / `SourceSetDirty` / `ProjectModelDirty` -> `Current`, preserving open editor overlays as separate metadata if replay follows. |
| `initialize(...)` failure | any state -> `Missing(BuildFailed)` with project inputs retained for explicit retry. |
| `shutdown()` / JVM process exit | any state -> `Missing(Disposed)` or `Missing(Restarting)`. |
| `didOpen` existing source | `Current` -> `EditorOverlayDirty(uri)` only if editor text differs from session/shadow text. |
| `didChange` existing source | add URI to `EditorOverlayDirty`; do not set `SourceSetDirty`. |
| `didSave` existing source | clear URI from `EditorOverlayDirty` if saved text is now the session-visible text, otherwise keep overlay dirty until rebuild/invalidation catches up. |
| new file inside source root | set `SourceSetDirty(NewFile)`. |
| delete/rename source file | set `SourceSetDirty(DeletedFile/RenamedFile)` and clear overlays for removed URI. |
| source-root/classpath/compiler-flag/Kotlin-version/JDK/project reload | set `ProjectModelDirty(...)`; clear source-set dirty after rebuild. |
| sidecar restart replay | `Missing(Restarting)` -> initialize -> replay overlays -> `Current` or overlay-dirty state based on replayed buffers. |

Ordinary text edits, saves, new files, deletes/renames, project-model reloads, and restart replay must call distinct setters so logs and metrics can show why rebuilds happen.

## Per-Request Behavior

| Freshness state | Interactive focused-file requests | Source-set-wide requests | Diagnostics | Rebuild behavior |
|-----------------|-----------------------------------|--------------------------|-------------|------------------|
| `Current` | Use session PSI unless a current overlay exists. | Proceed. | Proceed. | None. |
| `Missing` | Return unavailable/no-session result or wait only if request policy explicitly allows startup wait. | Same. | Return stale-safe empty result. | Initialize through server/bridge lifecycle. |
| `EditorOverlayDirty` | Completion, hover, and signature help use in-memory/dangling PSI. Definition on focused file may use overlay PSI for local targets, then session for cross-file targets if safe. | Avoid mixing stale session-wide results with overlay declarations unless feature policy says it is safe. | Defer/cancel, or use dangling diagnostics only after `ka-rd04`/diagnostic spike proves correctness. | No synchronous full rebuild. |
| `SourceSetDirty` | Existing files may still use dangling PSI against old context if safe; new/deleted/renamed files cannot rely on old source-set membership. | Defer or return stale-safe empty result. | Defer until source-set rebuild. | Async/debounced rebuild. |
| `ProjectModelDirty` | Return unavailable/stale-safe result unless a stale response is explicitly acceptable. | Defer. | Defer/clear. | Required project-model rebuild. |

Only these paths may synchronously call the full rebuild helper:

- explicit initialize/startup;
- explicit user/project reload command;
- a non-interactive command that declares it may block and has no stale-safe fallback.

These paths must not synchronously rebuild the full `StandaloneAnalysisAPISession`:

- completion;
- hover;
- signature help;
- semantic tokens/inlay hints/code lens;
- normal diagnostics after ordinary text edits;
- definition/declaration/implementation/type hierarchy/call hierarchy/references/rename/code actions during `EditorOverlayDirty`, unless the request explicitly chooses a stale-safe empty result or async rebuild trigger.

## File-Selection Policy by Feature

| Feature | File-selection policy |
|---------|-----------------------|
| Completion | Use current editor text. For existing source files, prefer dangling PSI against the original module/file context. Rebuild only for new source files that are not in the session source set. |
| Hover | Same focused-file policy as completion. Library/project symbol resolution may use the stable session context. |
| Signature help | Same focused-file policy as completion. |
| Diagnostics | Versioned and stale-safe. Ordinary edits mark diagnostics dirty. Use dangling diagnostics only if proven safe; otherwise debounce and rebuild/invalidate before publishing. |
| Definition/declaration | For local declarations in the focused dirty file, use overlay PSI. For cross-file symbols, use stable session results and map any shadow path through the response boundary. Do not leak internal URIs. |
| Implementation/type hierarchy/call hierarchy | Source-set-wide by nature. Defer or use stable session only when no focused-file overlay can affect the result. Never force a synchronous rebuild from a typing-triggered request. |
| References | Source-set-wide by nature. Must not report stale references from a dirty overlay file; either merge current overlay PSI for that file with session files or defer. |
| Code actions | Syntax/local code actions may use overlay PSI. Semantic edits/imports must use current text for the edited file and stable URI mapping. Broad source-set actions should defer under source-set/project dirtiness. |
| Rename/import edits | Use current editor version and Rust-side versioned apply-edit checks. The edit target URI must be the workspace URI, never a shadow/temp URI. |
| Semantic tokens/inlay hints/code lens | Prefer current overlay PSI for focused-file syntax/local facts. Defer semantic facts if they require a current FIR source-set view. |

## URI and Path Invariants

- Editor-facing responses use workspace/file URIs for project sources.
- Shadow-root paths, temp directories, LightVirtualFile paths, and future dangling-file names are internal implementation details.
- Every PSI-backed response crosses through a single mapping boundary: `fileUriForKtFile(...)` or its replacement.
- The mapping helper must handle workspace paths, shadow paths, temp virtual paths, URI decoding, and path normalization consistently.
- `buildFileUri(...)` must not be used directly for arbitrary source PSI unless the caller has already proven the path is editor-facing.
- Tests in `ka-rd06` must assert navigation and edit responses never expose the shadow root or temp virtual directory for project sources.

## Stale and Duplicate Visibility Risks

| Risk | Required coverage |
|------|-------------------|
| FIR sees both stale session `Foo.kt` and fresh virtual/dangling `Foo.kt`. | `ka-rd06`: duplicate declaration/redeclaration regression after ordinary edit. |
| Request reads disk/shadow text while Rust has newer editor text. | `ka-rd06`: completion/hover/diagnostics after unsaved edit prove current text is used or diagnostics are deferred. |
| Response leaks shadow/temp URI. | `ka-rd06`: definition/references/code-action edit URI mapping tests. |
| Background diagnostics publish obsolete version. | `ka-rd06`: versioned diagnostics test around rapid edit/debounce. |
| Sidecar restart loses open-buffer overlays or rebuilds from disk only. | `ka-rd06` or follow-up: restart replay test proves Rust document text/version is replayed before diagnostics. |
| Source-set-wide request mixes old session declarations with dirty overlay declarations. | `ka-rd06`: references/rename guard or documented stale-safe deferral behavior. |

## Implementation Follow-ups

Existing follow-up tickets are sufficient; no new ticket is required yet.

- `ka-rd03`: prototype dangling PSI for completion using this feature policy; measure fallback to current LightVirtualFile path.
- `ka-rd04`: spike standalone FIR/session cache invalidation and decide diagnostics policy for `EditorOverlayDirty`.
- `ka-rd05`: implement the typed freshness model, centralized rebuild policy, and background diagnostics deferral/cancellation.
- `ka-rd06`: add stale/duplicate visibility and URI mapping regression gates before the lifecycle refactor is considered complete.

Open questions:

- Can standalone Analysis API use `withDanglingFileResolutionMode` / `analyzeCopy` without the current global PSI validation bypass?
- Can standalone LL FIR cache storage be invalidated per file, or is that practical only inside Kotlin LSP's IntelliJ container?
- Should ordinary-edit diagnostics use dangling PSI immediately, or should they publish stale-safe empty/deferred results until debounced rebuild/invalidation?
- How much source-set-wide behavior can safely merge one dirty overlay file with stable session files?
- How should `server` be notified after automatic `bridge` restarts so it can replay open documents for restarts, not just initial sidecar startup?

## ka-rd03 Completion Prototype Notes

The first completion-only implementation uses `KtPsiFactory.createFile(...)` for current editor text when the URI already has a stable `KtFile` in the active `StandaloneAnalysisAPISession`, sets the temporary file's `originalFile` to the stable file, attaches the stable file's module through `contextModule`, and analyzes it with `analyzeCopy(..., KaDanglingFileResolutionMode.PREFER_SELF)`. The mode is isolated behind `COMPLETION_DANGLING_FILE_RESOLUTION_MODE` so it can be swapped if needed. `PREFER_SELF` is the correct choice for full-file editor overlays: every keystroke can mutate declaration signatures in the copy, and `PREFER_SELF` lets reference resolution inside the dangling file see the copy's own declarations. The earlier `IGNORE_SELF` setting (ka-rd03) caused overlay-only local variables to fall back to `Any` at receiver positions, exposing only the three `Any` members at member-access completions (ka-d4n8); `IGNORE_SELF` would only be appropriate for body-only diffs, which the current overlay path never produces.

Completion falls back to the existing `findKtFile(...)` / LightVirtualFile path if dangling PSI creation or dangling analysis fails. The fallback is quiet in normal logs; with completion metrics enabled or requested, responses include `fields.completionFileMode`, `fields.danglingAnalysisMode`, `counts.danglingFallback`, and `fields.danglingFallbackReason` when a fallback occurs. The prototype still leaves the existing standalone Analysis API PSI validation bypass enabled after session initialization; removing that bypass is a separate cleanup decision.
