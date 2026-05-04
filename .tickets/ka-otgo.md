---
id: ka-otgo
status: open
deps: [ka-4ssc, ka-cyb9]
links: []
created: 2026-05-04T12:34:55Z
type: feature
priority: 2
assignee: Jens Kouros
parent: ka-4qf5
tags: [kotlin, imports, lsp, sidecar, auto-import]
---
# Post-edit auto-import for unique unresolved type candidates

After re-analysis of a changed document, automatically insert an import when an unresolved type reference in a supported type position resolves to exactly one candidate.

## Design

When the sidecar's debounced re-analysis detects an UNRESOLVED_REFERENCE diagnostic in a type position (property type annotation or function return type), and findImportCandidates returns exactly one match:
1. Build the import edit using the shared import-edit utility.
2. Attach the current document version.
3. Send the edit via the workspace/applyEdit transport.

## Typing debounce / staleness

Active typing produces rapid document changes. Auto-import must not fire on incomplete names. Safeguards:
- Only act on diagnostics from a completed re-analysis cycle (after the debounce window).
- Compare document version at planning time vs. current version; drop if mismatched.
- Only trigger for identifiers that look like complete type names (start with uppercase, match [A-Z][A-Za-z0-9_]*).
- Do not auto-import if the unresolved name is a prefix of a longer token being typed (check if cursor is immediately after the unresolved range).

## No-op cases

- Already imported: buildImportEdit returns null.
- Same-package: buildImportEdit returns null.
- Multiple candidates: do not auto-import (code actions handle this).
- Zero candidates: no action.
- Unsupported type positions (parameter types, destructuring): skip.
- Document changed since analysis: drop the edit.

## Code areas

- Sidecar: post-analysis hook in the re-analysis pipeline
- BridgeContext.kt: planTypeImportAssist, findImportCandidates, shared import-edit utility
- Rust bridge: workspace/applyEdit transport (from prior ticket)

## Acceptance criteria

- Typing val service: UserService = ... where UserService is unambiguously importable results in automatic import insertion.
- No auto-import fires when multiple candidates exist.
- No auto-import fires for already-imported or same-package types.
- No auto-import fires while the user is still typing (debounce + version check).
- Stale edits are dropped if the document changes between analysis and edit delivery.
- Sidecar integration tests cover unique-match, no-op, and staleness scenarios.
