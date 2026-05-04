---
id: ka-cyb9
status: closed
deps: []
links: []
created: 2026-05-04T12:34:55Z
type: task
priority: 2
assignee: Jens Kouros
parent: ka-4qf5
tags: [kotlin, imports, sidecar, refactor]
---
# Extract shared import-edit utility from BridgeContext

Extract buildImportEdit and computeImportInsertion from BridgeContext into a focused import-edit utility that can be shared across code-action imports, post-edit auto-import, and the explicit type definition work in ka-pg9l.

The current code lives in BridgeContext.kt:785-810. It handles same-package filtering, existing-import deduplication, and correct insertion placement. This is the right behavior, but it is buried inside BridgeContext and coupled to its internal state.

## Scope

- Extract buildImportEdit and computeImportInsertion into a standalone helper (e.g. ImportEditHelper or a top-level utility in a new file).
- Keep the same contract: takes uri, ktFile, document, fqn; returns a JsonObject edit or null.
- Ensure addTypeImportCodeActions in BridgeCodeActions.kt:600 and addOrganizeImportsAction in BridgeCodeActions.kt:481 continue to work through the extracted utility.
- Keep findImportCandidates accessible from the utility or pass candidates in from the caller.
- Do not change behavior — this is a pure extraction refactor.

## Code areas

- BridgeContext.kt: buildImportEdit (line 785), computeImportInsertion (line 806), findImportCandidates
- BridgeCodeActions.kt: addTypeImportCodeActions (line 586), addOrganizeImportsAction (line 481)
- Both ka-pg9l (explicit type actions) and the post-edit auto-import work will consume this utility.

## Acceptance criteria

- buildImportEdit and computeImportInsertion are callable without a BridgeContext receiver.
- Existing code-action import tests still pass.
- No behavioral change.
