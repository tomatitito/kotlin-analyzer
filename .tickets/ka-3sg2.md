---
id: ka-3sg2
status: open
deps: []
links: [ka-pg9l, ka-fm0u]
created: 2026-04-02T11:50:25Z
type: feature
priority: 2
assignee: Jens Kouros
tags: [kotlin, imports, lsp, code-actions, zed]
---
# Auto-import unresolved types when adding explicit type annotations

When a user manually writes an explicit Kotlin type annotation in a file and the referenced type is not imported yet, the analyzer should automatically add the required import instead of leaving the file in an unresolved state.

Scope this to editor-driven explicit type annotations first, such as `val service: UserService = ...` and function return types where the inserted short name resolves uniquely in project or dependency symbols. Defer parameter types until the property and function flows are solid.

This is separate from the existing type-definition generation work: that ticket covers analyzer-generated type text edits, while this feature covers the user typing a valid type name directly in the editor and expecting the import list to stay correct automatically.

## Design

Implement this as a sidecar-owned post-edit import assist that runs after successful re-analysis of a changed document. When semantic analysis detects an unresolved type reference in a supported Kotlin type position, the sidecar should resolve import candidates and decide whether to auto-import or surface code actions.

Recommended first slice:
- Detect unresolved references specifically in Kotlin type positions for property type annotations and function return types.
- Resolve candidate symbols from project source and classpath symbols already indexed by the sidecar.
- If there is exactly one valid import candidate, return an edit that inserts the import automatically.
- If there are multiple valid candidates, do not auto-import. Instead, surface one code action per candidate using the stable title format `Import '<ShortName>' from <fully.qualified.Name>`.
- Reuse existing import insertion/formatting logic if available; otherwise add a small utility that inserts into the correct import section and preserves package declaration spacing.
- Treat the following as no-op cases: already imported symbols, same-package symbols that require no import, unsupported type positions, incomplete or unstable references during active typing, and cases with no valid import candidates.
- Drop stale edits that target an outdated document version.

Likely code areas:
- `sidecar/src/main/kotlin/dev/kouros/sidecar/CompilerBridge.kt` for analysis and edit planning
- Rust server edit/code-action plumbing only if existing transport is insufficient
- integration coverage in sidecar tests, with server coverage only if a new transport path is introduced

Relationship to existing work:
- `ka-pg9l` explicitly deferred automatic import insertion for new short names. Link this ticket as the broader manual-typing follow-up and coordinate any shared import-edit helper or candidate-resolution helper used by both manual typing and analyzer-generated explicit type actions.

## Implementation Outline

1. Extend the sidecar re-analysis flow to detect unresolved references in supported Kotlin type positions after a document change.
2. Add a helper that classifies supported type positions and extracts the unresolved short name for property type annotations and function return types.
3. Resolve import candidates for that short name from project symbols and indexed classpath symbols, filtering out already-imported and same-package symbols.
4. If exactly one valid candidate remains, plan an import insertion edit using shared import placement and deduplication logic.
5. If multiple valid candidates remain, build one code action per candidate using the title format `Import '<ShortName>' from <fully.qualified.Name>`.
6. Reuse or extract a shared import-edit utility so both automatic edits and candidate-specific code actions insert imports in the same correct location and formatting style.
7. Ensure auto-import edits are only applied against the analyzed document version and are dropped if the document has changed since planning.
8. Add sidecar integration tests covering unique-match auto-import, already-imported and same-package no-op behavior, and ambiguous-match candidate code actions.

## Concrete Implementation Plan

### Workstream 1: Type-position detection and candidate planning

Owner: sidecar

- Extend the sidecar re-analysis pipeline to inspect unresolved references after successful analysis of a changed document.
- Add a helper that identifies supported type positions and extracts the unresolved short name from property type annotations and function return types.
- Add a candidate-resolution helper that searches project symbols and indexed classpath symbols for matching importable definitions.
- Define the internal planning result for:
  - unique import candidate
  - ambiguous import candidates
  - no-op or unsupported cases

Suggested touch points:
- `sidecar/src/main/kotlin/dev/kouros/sidecar/CompilerBridge.kt`
- nearby sidecar analysis helpers or new helper files if the logic becomes large

### Workstream 2: Shared import edit utility

Owner: sidecar

- Reuse or extract a shared utility that inserts an import in the correct location relative to the package declaration and existing imports.
- Ensure the utility deduplicates existing imports and treats same-package symbols as no-op.
- Make this utility the single path used by both automatic import edits and ambiguous import code actions.

Suggested touch points:
- `sidecar/src/main/kotlin/dev/kouros/sidecar/CompilerBridge.kt`
- a new sidecar import-edit helper if separation improves reuse with `ka-pg9l`

### Workstream 3: Automatic import application path

Owner: sidecar, with Rust changes only if existing transport is insufficient

- Apply the unique-match import edit after successful re-analysis.
- Ensure the edit is tied to the analyzed document version and is dropped if the document changes before the edit is applied.
- Confirm whether the current server and bridge path can already deliver these edits without adding a new protocol shape.
- If additional transport is required, keep Rust changes minimal and confined to existing edit-plumbing responsibilities.

Suggested touch points:
- `sidecar/src/main/kotlin/dev/kouros/sidecar/CompilerBridge.kt`
- Rust bridge/server plumbing only if required by the existing protocol

### Workstream 4: Ambiguous import code actions

Owner: sidecar, with Rust changes only if existing transport is insufficient

- For ambiguous matches, build one code action per valid candidate using the title format `Import '<ShortName>' from <fully.qualified.Name>`.
- Reuse the shared import-edit utility so candidate-specific actions produce the same import text and placement as the automatic path.
- Ensure these actions are only surfaced for supported type-position ambiguity cases and not for unrelated unresolved references.

Suggested touch points:
- `sidecar/src/main/kotlin/dev/kouros/sidecar/CompilerBridge.kt`
- existing code-action plumbing on the Rust side only if required

### Workstream 5: Test coverage

Owner: split by layer

- Add sidecar integration tests for:
  - unique-match auto-import
  - already-imported no-op
  - same-package no-op
  - ambiguous matches producing one code action per candidate
  - no incorrect auto-import on ambiguity
- Add Rust/server tests only if a new transport path or edit-delivery mechanism is introduced.

Suggested touch points:
- sidecar integration test suite around `CompilerBridge`
- Rust test coverage only where new transport behavior is added

### Parallelization and sequencing

- Workstream 1 and Workstream 2 can proceed in parallel if the planning result shape and import-edit utility contract are agreed first.
- Workstream 3 depends on Workstream 1 and Workstream 2.
- Workstream 4 depends on Workstream 1 and Workstream 2, but can proceed in parallel with Workstream 3 once shared contracts are stable.
- Workstream 5 should be developed alongside the implementation streams, with sidecar coverage landing with each sidecar change and Rust coverage only where transport changes occur.

## Acceptance Criteria

- In a Kotlin file, when a user adds an explicit type annotation using a short type name that is not yet imported and the target symbol resolves uniquely, the analyzer adds the required import automatically.
- The import is inserted in the correct location relative to the package declaration and existing imports.
- The feature works for property type annotations and function return types.
- Ambiguous matches do not auto-import the wrong symbol.
- When multiple valid import candidates exist, the analyzer surfaces one code action per candidate using the title format `Import '<ShortName>' from <fully.qualified.Name>`.
- Existing imports are not duplicated.
- Same-package symbols do not produce duplicate or unnecessary imports.
- Add regression tests covering successful auto-import, already-imported types, same-package symbols, and ambiguous candidates with candidate-specific code actions.
