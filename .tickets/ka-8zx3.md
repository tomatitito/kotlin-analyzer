---
id: ka-8zx3
status: open
deps: [ka-j5fx]
links: [ka-wj15, ka-aj6x]
created: 2026-04-07T10:35:36Z
type: feature
priority: 2
assignee: Jens Kouros
tags: [code-actions, navigation, lsp, zed, kotlin]
---
# Add Kotlin code action to jump between interface definitions and implementations

Add Kotlin code actions that let the user jump from an interface definition to one of its implementations, and from a class or object that implements an interface back to that interface definition. Keep the first slice conservative and editor-friendly: only offer the action when the target set can be determined semantically from the current symbol, and avoid heuristic text matching. Prefer concrete action titles over an interactive secondary prompt if that keeps the UX simpler in Zed.

Suggested first-slice UX:
- On an interface declaration: offer one action per implementation, for example `Go to Implementation: FooServiceImpl`, when at least one concrete implementation can be resolved.
- On a class or object declaration with exactly one implemented interface: offer `Go to Interface: FooService`.
- On a class or object declaration with multiple implemented interfaces: offer one action per interface rather than a second prompt.

Implementation notes:
- Reuse semantic resolution from the sidecar instead of filename/path conventions.
- Keep the Rust frontend focused on transport and command execution; resolution logic belongs in Kotlin.
- Reuse the command-based code action plumbing already established for analyzer-owned commands, but ensure the navigation transport is compatible with Zed.
- The first slice only needs direct interface <-> implementing class/object navigation; defer sealed hierarchies, abstract-class hierarchies, method-level override navigation, and fuzzy ranking across large implementation sets.

This should behave as a navigation-oriented code action, not as a replacement for standard go-to-definition or find-references.

## Acceptance Criteria

- On an interface declaration with one resolved implementation, the analyzer offers a navigation code action to that implementation and invoking it opens the target declaration in the editor.
- On an interface declaration with multiple resolved implementations, the analyzer offers one concrete navigation code action per implementation.
- On a class or object declaration that directly implements one interface, the analyzer offers a navigation code action back to that interface definition.
- On a class or object declaration that directly implements multiple interfaces, the analyzer offers one concrete navigation code action per interface.
- The action is not offered when semantic resolution is ambiguous or no interface/implementation target can be resolved confidently.
- Add sidecar tests for interface-to-implementation and implementation-to-interface target discovery.
- Add Rust/LSP tests for command transport and client-visible navigation behavior for the chosen Zed-compatible transport.
- Update command/action protocol docs if a new analyzer-owned command shape is added.
