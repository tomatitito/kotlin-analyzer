---
id: ka-rd02
status: closed
deps: [ka-p0fi, ka-rd01]
links: [ka-kyvv]
created: 2026-05-05T20:32:00Z
type: task
priority: 0
assignee: Jens Kouros
parent: ka-36lk
tags: [perf, sidecar, architecture, analysis-api]
---
# Design the sidecar file and session lifecycle model

Define the contract for how editor text, PSI files, shadow-session files, Analysis API sessions, and FIR-visible source files move through the Rust server and JVM sidecar.

The current implementation uses one broad `sessionDirty: Boolean` concept for multiple concerns: ordinary editor text changes, shadow-root rebuilds, diagnostics freshness, missing sessions, and structural project changes. That makes interactive latency hard to reason about and creates stale/duplicate file risks when physical files, shadow files, LightVirtualFiles, and future dangling PSI coexist.

This ticket should produce the design spec that `ka-rd03`, `ka-rd04`, `ka-rd05`, and `ka-rd06` implement or test against.

## Inputs

- `ka-p0fi`: PSI/FIR terminology and file-model vocabulary.
- `ka-rd01`: selected near-term performance direction and upstream research.
- `plans/active/session-file-leak.md`: existing stale/duplicate file failure mode and shadow-root workaround.
- Current sidecar lifecycle code around `updateFile`, `removeFile`, `ensureSessionCurrent`, shadow source roots, `findKtFile`, `fileUriForKtFile`, diagnostics, completion, hover, navigation, references, signature help, and code actions.
- Rust architecture invariant: `server` owns LSP document/project state and orchestrates replay; `bridge` must not own or pull from `state`/`project`; the sidecar owns Analysis API state only.

## Scope

- Add or update one durable design note under `docs/` or `plans/active/` describing the intended lifecycle model.
- Classify each change type and expected sidecar action:
  - open existing source file;
  - ordinary text edit in an already-known source file;
  - save of an already-known source file;
  - close of a file with or without unsaved changes;
  - new source file inside a known source root;
  - deleted/renamed source file;
  - source-root/classpath/compiler-flag/Kotlin-version/project-model change;
  - sidecar crash/restart/replay from Rust state.
- For each change type, state whether the existing `StandaloneAnalysisAPISession` may be reused, whether a dangling/in-memory PSI path is allowed, whether background diagnostics should be invalidated/deferred, and whether a full session rebuild is required.
- Define the ownership boundary:
  - Rust server is the source of truth for editor URI, document text, LSP version, project model, and sidecar replay inputs.
  - JVM sidecar is the source of truth for Analysis API session objects, PSI/FIR lookup strategy, shadow roots, temporary files, and semantic cache state.
  - `bridge` stays a transport/process layer; replay orchestration remains in `server`.
- Define URI/path invariants:
  - editor-facing responses use workspace/file URIs, never shadow/temp/internal paths for project sources;
  - internal shadow/temp paths must be mapped back before crossing the sidecar response boundary;
  - path comparison must handle workspace path, shadow path, virtual file path, and URI decoding consistently.
- Define per-feature file-selection policy for:
  - completion;
  - hover;
  - signature help;
  - diagnostics;
  - definition/declaration;
  - implementation/type hierarchy/call hierarchy where relevant;
  - references;
  - code actions/rename/import edits where relevant.
- Identify stale and duplicate visibility risks:
  - FIR sees both stale session file and fresh virtual/dangling file;
  - request reads disk/shadow text when Rust has newer editor text;
  - response leaks shadow/temp URI;
  - background diagnostics publish results for an obsolete document version;
  - sidecar restart loses open-buffer overlays or rebuilds from disk only.
- List the exact implementation follow-ups that should be amended or created after the design is written.

## Required Design Decisions

- Replace `sessionDirty: Boolean` with an explicit typed session freshness model, unless the design proves this is unnecessary. The design should sketch the intended enum/sealed-class shape and name the dimensions it tracks.
- The freshness model must distinguish at least:
  - current session;
  - missing/disposed session;
  - ordinary editor-text overlay dirty for known files;
  - source-set dirty because the FIR-visible file set changed;
  - project-model dirty because classpath/source roots/compiler flags/Kotlin version/JDK inputs changed.
- Decide whether the model is a single enum/sealed class with payloads, multiple orthogonal state fields, or a small state machine. Payloads should be considered for changed URIs and dirty reasons.
- Define legal state transitions and which code paths may set each state. Ordinary text edits, saves, new files, deletes/renames, project-model reloads, and sidecar restart replay must not all use the same transition.
- Define per-request behavior for each freshness state:
  - proceed against existing session;
  - proceed through in-memory/dangling PSI;
  - defer/cancel background diagnostics;
  - require async rebuild;
  - require synchronous rebuild only if explicitly allowed.
- Which operations are allowed to call `ensureSessionCurrent()` synchronously on the interactive request path.
- Whether ordinary edits to existing files are represented as:
  - in-memory/dangling PSI against the stable module context;
  - shadow-root session rebuild;
  - LightVirtualFile overlay;
  - a temporary hybrid until `ka-rd03`/`ka-rd04` resolve feasibility.
- Whether diagnostics after ordinary edits are expected to use current in-memory/dangling PSI immediately, wait for debounced rebuild/invalidation, or publish stale-safe empty/deferred results until the semantic state is current.
- How new/deleted/renamed source files differ from ordinary edits to existing files.
- How sidecar restart replay reconstructs open-buffer overlays without giving the sidecar authority over Rust document state.

## Expected Flow Diagrams

Include at least these ASCII diagrams:

```text
didChange existing Foo.kt
    -> Rust updates document store/version
    -> Rust sends sidecar text update
    -> sidecar updates editor-text overlay
    -> completion/hover/signature help use current text without full rebuild
    -> diagnostics are scheduled/deferred according to policy
    -> published diagnostics are version-checked before reaching the editor
```

```text
structural/project-model change
    -> Rust updates project model
    -> Rust sends explicit rebuild input to sidecar
    -> sidecar builds one FIR-visible source set
    -> sidecar rebuilds Analysis API session
    -> semantic requests resume against the new session
```

```text
sidecar restart
    -> server restarts bridge process
    -> server replays project model
    -> server replays open document text/version
    -> sidecar reconstructs Analysis API state and editor overlays
```

## Non-Goals

- Do not implement dangling PSI, FIR cache invalidation, or scheduling changes in this ticket.
- Do not perform a broad sidecar refactor beyond documenting the current call sites and required follow-up work.
- Do not decide a long-term rust-analyzer/Salsa-style database rewrite; `ka-rd01` should already classify that as long-term inspiration unless its conclusion changes.

## Acceptance Criteria

- A design note exists under `docs/` or `plans/active/` and is linked from `docs/INDEX.md` if it is a new `docs/` page.
- The design includes a lifecycle matrix covering all change types listed in Scope.
- The design includes the three required flow diagrams and explicitly covers ordinary edit -> interactive request -> diagnostics.
- The design gives a per-feature file-selection policy for completion, hover, signature help, diagnostics, navigation, references, and code actions/rename/import edits where relevant.
- The design proposes a typed replacement for `sessionDirty: Boolean`, including state names, payloads if needed, legal setters/transitions, and consumers.
- The design explicitly states which request paths must not synchronously rebuild the full `StandaloneAnalysisAPISession`.
- The design defines editor URI vs shadow/internal URI invariants and names the helper boundary where mapping must happen.
- The design identifies stale/duplicate PSI/FIR visibility risks and maps each risk to required regression coverage in `ka-rd06`.
- The design lists required code changes and updates/amends follow-up tickets (`ka-rd03`, `ka-rd04`, `ka-rd05`, `ka-rd06`, and any new tickets) with notes or edited acceptance criteria.
- The design is consistent with the Rust module dependency constraint: `server` orchestrates replay from `state`/`project`, `bridge` remains below `server`, and the sidecar owns only Analysis API state.
