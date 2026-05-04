---
id: ka-ceoo
status: closed
deps: []
links: [ka-gjfa]
created: 2026-03-31T13:03:41Z
type: task
priority: 2
assignee: Jens Kouros
tags: [lsp, code-actions, infrastructure, zed]
---
# Add command-based code action execution and navigation support in Rust LSP server

Add the Rust LSP server infrastructure needed to preserve and execute command-based code actions from the sidecar. This should cover capability advertisement, code action parsing, command execution plumbing, and client-visible navigation hooks needed for flows such as opening an existing test file or create-and-open behavior.

## Acceptance Criteria

- The Rust LSP server advertises execute-command capability for supported analyzer commands.
- Code actions returned from the sidecar can carry commands through Rust parsing instead of being dropped.
- The server implements command execution for analyzer-owned commands needed by code actions.
- The server can trigger client-visible navigation for an existing target file/location via the chosen LSP mechanism.
- Add tests covering command parsing, command execution, and navigation-oriented behavior at the server layer.

## Implementation Decisions

- Standardize the first slice on command-based code actions rather than `WorkspaceEdit.documentChanges` / `CreateFile`.
- Keep command ownership analyzer-scoped and explicit. The first slice should support only these analyzer-owned command IDs:
  - `kotlin-analyzer.openTestTarget`
  - `kotlin-analyzer.createAndOpenTestTarget`
- Define one shared JSON argument contract for these commands.
  - `kotlin-analyzer.openTestTarget` arguments:
    - `targetUri: string` required
    - `selection: { startLine: number, startCharacter: number, endLine: number, endCharacter: number }` optional
  - `kotlin-analyzer.createAndOpenTestTarget` arguments:
    - `targetUri: string` required
    - `targetPath: string` required
    - `initialContents: string` required
    - `selection: { startLine: number, startCharacter: number, endLine: number, endCharacter: number }` optional
- The payload should carry concrete target information rather than requiring Rust to recompute Kotlin planning decisions. That means the Kotlin sidecar/planner decides the target path/URI, class name, and initial file contents; Rust executes the transport behavior.
- Use `window/showDocument` for existing-file open/reveal behavior. Pass the target URI and selection when available.
- Keep orchestration in `server`. `bridge` stays transport-only and must not grow dependencies on `state` or `project`.
- Preserve compatibility with existing edit-only code actions. This ticket extends parsing/execution; it must not regress text-edit handling.
- The create-and-open command is expected to create the file on disk from the provided payload, then issue the same `window/showDocument` navigation step against `targetUri`.
- Unsupported command IDs or malformed argument payloads should fail with a clear server-side error and no partial file creation.
- Store the command contract in one checked-in JSON file instead of scattering string literals:
  - Add a canonical contract file at `protocol/analyzer-commands.json`
  - Kotlin and Rust should both consume or mirror this file as the source of truth for command IDs and argument keys
  - Add a short human-readable contract note in `docs/command-code-actions.md`

## Concrete Scope

- In scope:
  - capability advertisement for supported analyzer commands
  - parsing and preserving command-bearing code actions from sidecar responses
  - server-side dispatch for the first supported analyzer commands
  - client-visible navigation/open behavior for an existing target URI/location
  - enough command execution plumbing to support later create-and-open flows
  - server-layer tests for the above
- Out of scope:
  - Kotlin-side planner logic for test discovery or test file generation rules
  - broad generic command execution for arbitrary third-party commands
  - expanding code action transport to unrelated protocols beyond the first standardized analyzer command flow
  - full end-to-end unit-test action behavior in this ticket

## Implementation Outline

1. Define the first supported analyzer command contract.
   - Use `kotlin-analyzer.openTestTarget` and `kotlin-analyzer.createAndOpenTestTarget`.
   - Keep the argument keys exactly `targetUri`, `targetPath`, `initialContents`, and optional `selection`.
   - Add the canonical contract in `protocol/analyzer-commands.json`.
   - Document the same contract briefly in `docs/command-code-actions.md`.
   - Mirror this transport shape in the Kotlin sidecar and Rust server from the JSON contract without Rust recomputing the test target.

2. Add Rust-side capability advertisement.
   - Advertise `workspace/executeCommand` support for the selected analyzer-owned commands.
   - Keep command registration ownership in `server`, consistent with current module boundaries.

3. Preserve command-bearing code actions during parsing.
   - Extend the Rust code action parsing path so `CodeAction.command` is retained instead of normalized away.
   - Keep existing edit handling intact for edit-only actions.

4. Add command dispatch and execution plumbing in the Rust server.
   - Route supported analyzer-owned commands through a server-level dispatcher in `server/src/server.rs`.
   - Keep `server` responsible for orchestration; do not push `state` or `project` dependencies down into `bridge`.
   - Implement `kotlin-analyzer.openTestTarget` by validating the payload and issuing `window/showDocument`.
   - Implement `kotlin-analyzer.createAndOpenTestTarget` by writing `initialContents` to `targetPath` when absent, then issuing `window/showDocument` for `targetUri`.

5. Implement client-visible navigation for existing targets.
   - Use `window/showDocument` as the navigation mechanism for this slice.
   - Cover the existing-file navigation path first so later open-or-create flows can reuse the same transport.

6. Add server-layer tests before feature wiring.
   - Test capability advertisement for supported commands.
   - Test command preservation when parsing sidecar code actions.
   - Test command dispatch/execution for supported analyzer commands.
   - Test navigation-oriented behavior at the server layer using the chosen client interaction mechanism.

## Required Test Cases

- Initialization test proves the server advertises execute-command support with the expected analyzer command list.
- Code action parsing test proves a sidecar-returned `CodeAction.command` survives Rust parsing unchanged, including `kotlin-analyzer.openTestTarget` or `kotlin-analyzer.createAndOpenTestTarget` plus structured arguments.
- Backward-compatibility test proves an edit-only code action still parses and behaves as before.
- Command-dispatch test proves unsupported command IDs are rejected safely and supported analyzer command IDs route to the correct server handler.
- Existing-target navigation test proves executing `kotlin-analyzer.openTestTarget` emits `window/showDocument` with the expected `targetUri` and optional `selection`.
- Create-flow plumbing test proves executing `kotlin-analyzer.createAndOpenTestTarget` writes the requested file contents and then emits `window/showDocument` for the created target.
- Invalid-payload test proves missing required keys such as `targetUri`, `targetPath`, or `initialContents` fail cleanly without partial side effects.

## Notes For Implementers

- Treat this ticket as infrastructure for `ka-26kw`, not as the place to invent Kotlin test-target planning rules.
- Before writing code, pin the command names and argument keys in:
  - `protocol/analyzer-commands.json`
  - `docs/command-code-actions.md`
- Kotlin and Rust may expose local constants/helpers for ergonomics, but they should derive from or exactly mirror `protocol/analyzer-commands.json` rather than inventing independent names.
- The current parsing seam that drops `CodeAction.command` is `parse_code_actions()` in `server/src/server.rs`; capability advertisement also lives in `server/src/server.rs`.
- If the chosen client navigation mechanism has limitations in Zed, document the exact observed behavior in a ticket note and keep the command contract stable.
