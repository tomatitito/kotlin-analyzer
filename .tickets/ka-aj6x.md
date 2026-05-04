---
id: ka-aj6x
status: closed
deps: []
links: [ka-8zx3, ka-wj15]
created: 2026-04-08T08:43:44Z
type: bug
priority: 1
assignee: Jens Kouros
tags: [navigation, lsp, zed, kotlin, analysis-api]
---
# Fix go-to-declaration for interface method implementations

Standard go-to-declaration does not work when the cursor is on a concrete Kotlin method that implements an interface method. From an override such as `override fun foo(...)`, the analyzer should navigate to the corresponding interface member declaration, but today that path does not resolve in the editor.

Scope this specifically to method-level navigation from implementation -> interface declaration, not just type-level class/object -> interface navigation. The target should be resolved semantically from override relationships in the Kotlin sidecar rather than filename or text heuristics.

Implementation notes:
- Treat this as the reverse of interface-method go-to-implementation: from a concrete overriding member, surface the overridden interface member declaration through the standard definition/declaration path that Zed actually uses for this workflow.
- Keep Rust focused on LSP transport/orchestration; override target discovery belongs in Kotlin.
- Handle direct implementations conservatively first; if multiple interface declarations are inherited through a complex hierarchy, return only confident semantic targets.
- Prefer existing LSP definition/declaration plumbing where possible instead of introducing a second navigation-only code action.
- Keep the first slice to interface-member declarations; broader abstract-class/super-method hierarchy browsing can stay out of scope.

Current behavior:
- On a method implementation of an interface member, invoking go-to-declaration in the editor does not open the interface method declaration.

Expected behavior:
- On a method implementation of an interface member, invoking go-to-declaration opens the corresponding interface method declaration when it can be resolved confidently.

## Acceptance Criteria

- On a Kotlin method declaration that directly overrides an interface method, invoking go-to-declaration opens the interface member declaration in the editor.
- The navigation is based on semantic override resolution, not filename/path/text heuristics.
- Common Kotlin cases work correctly, including class and object implementations of interface members.
- The analyzer does not navigate to incorrect targets when the override relationship cannot be resolved confidently.
- Add sidecar coverage for implementation-member -> interface-member target discovery.
- Add Rust/LSP regression coverage for the client-visible definition/declaration request path used by Zed.
- Update docs if the bridge or LSP method table changes.


## Notes

**2026-04-08T09:00:42Z**

Implemented on 2026-04-08: Kotlin definition now resolves direct overridden interface members when invoked on override method declarations. Verified with ./gradlew test --tests dev.kouros.sidecar.InterfaceNavigationCodeActionIntegrationTest and cd server && cargo test --features integration test_goto_definition_resolves_interface_method_declaration_from_override -- --nocapture after rebuilding sidecar runtime payloads.
