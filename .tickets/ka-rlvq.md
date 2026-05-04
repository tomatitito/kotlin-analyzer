---
id: ka-rlvq
status: open
deps: []
links: [ka-q5t5, ka-2pai, ka-888e]
created: 2026-03-30T07:26:54Z
type: bug
priority: 2
assignee: Jens Kouros
external-ref: TOM-74
parent: ka-7dbe
tags: [pebble, bug, lsp, definition, zed]
---
# Pebble bug: template-to-template go-to-definition fails in editor

Go to Definition from one Pebble template to another is not working in editor testing, even though include/extends/import navigation is part of the Pebble MVP and appears partially implemented in the sidecar. This should work for template literals in {% include %}, {% extends %}, and {% import %}.

## Acceptance Criteria

- Reproducing fixture demonstrates current failure from a Pebble template literal to its target template.
- Go to Definition from {% include %}, {% extends %}, and {% import %} resolves to the referenced template in Zed when the file is recognized as Pebble.
- If editor language registration is the cause, the fix includes the necessary extension/grammar wiring.
- Automated regression coverage exists at the appropriate layer (server/sidecar and/or editor verification notes).

## Fix Outline

1. **Route Pebble documents through Pebble bridge methods in the Rust server**
   - Detect document kind from `languageId` and file suffix (`.peb`, `.pebble`).
   - Store the detected kind in the document store for open documents.
   - Use document-kind-aware method selection for:
     - `didOpen`
     - `didChange`
     - `didClose`
     - `textDocument/definition`
     - `textDocument/references`
   - For Pebble files, forward requests to:
     - `pebble/textDocument/didOpen`
     - `pebble/textDocument/didChange`
     - `pebble/textDocument/didClose`
     - `pebble/definition`
     - `pebble/references`

2. **Prevent Pebble documents from going through Kotlin-only analysis paths**
   - Skip `analyze` for Pebble documents in normal document processing.
   - Skip Kotlin analysis replay for Pebble files after sidecar restart.
   - Keep replay of open-document state, but use Pebble-specific open/change notifications for Pebble files.

3. **Verify editor wiring is correct**
   - Confirm the Zed extension registers Pebble as a language and attaches `kotlin-analyzer` to it.
   - Confirm Pebble files opened in Zed are recognized with `languageId = pebble` or are at least detected by suffix fallback.

4. **Add regression coverage**
   - Rust server tests for document-kind detection and method routing.
   - End-to-end/server tests covering Pebble definition requests from a template literal to the target template.
   - Verification notes for Zed covering `{% include %}`, `{% extends %}`, and `{% import %}` navigation.

5. **Validate with fixture/manual repro**
   - Open a Pebble fixture project in Zed.
   - Trigger Go to Definition on template literals in `include`, `extends`, and `import`.
   - Confirm navigation reaches the referenced template file.
