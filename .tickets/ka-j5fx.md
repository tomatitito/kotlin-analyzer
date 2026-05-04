---
id: ka-j5fx
status: open
deps: []
links: []
created: 2026-03-31T17:16:09Z
type: bug
priority: 1
assignee: Jens Kouros
parent: ka-26kw
tags: [zed, lsp, code-actions]
---
# Zed compatibility: unit-test code action navigation ignored because window/showDocument is unhandled

The Kotlin unit-test code action planner is producing valid targets and the Rust execute-command path no longer errors on Zed's null showDocument response, but Zed 0.229.0 still does not perform navigation for the command.

Observed in /Volumes/sourcecode/meta-breuninger/entdecken/reco/outfit-genie. The project has the expected conservative layout (src/main/kotlin <-> src/test/kotlin), and mirrored tests like OutfitForFarbIdService.kt -> OutfitForFarbIdServiceTest.kt already exist.

Current behavior:
- The code action appears in Zed.
- Executing it no longer shows an error after the null-response compatibility patch.
- Zed logs: "Language server with id 3 sent unhandled notification window/showDocument" and does not open or focus the target file.

Relevant evidence:
- ~/Library/Logs/Zed/Zed.log shows repeated "unhandled notification window/showDocument" entries for kotlin-analyzer.
- /tmp/kotlin-analyzer-server.log shows the sidecar returning the unit-test code action correctly for outfit-genie source files.

Implication:
- Open-existing-test is blocked in Zed with the current transport.
- Create-new-test may need a separate Zed-compatible transport if we want visible editor behavior there too.

## Acceptance Criteria

- Reproduce the problem in Zed against a real project such as /Volumes/sourcecode/meta-breuninger/entdecken/reco/outfit-genie and document the exact client behavior.
- Determine a Zed-compatible transport for this UX instead of relying on window/showDocument alone.
- For existing mirrored tests, invoking the action in Zed should open or focus the target test file.
- For missing mirrored tests, invoking the action in Zed should create the target test file and make the result visible to the user in the editor.
- Add regression coverage for the chosen transport at the Rust LSP layer, and update docs/command-code-actions.md if the transport contract changes.
