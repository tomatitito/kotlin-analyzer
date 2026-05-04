---
id: ka-7dbe
status: open
deps: [ka-2pai]
links: []
created: 2026-03-28T21:36:34Z
type: epic
priority: 2
assignee: Jens Kouros
external-ref: TOM-64
tags: [pebble, roadmap, lsp, needs-design]
---
# Pebble support: Kotlin-Pebble navigation roadmap

Implements the Pebble support roadmap in phased work, starting with the MVP for Kotlin-Pebble go-to-definition and references.

Execution chain:
1. TOM-65 extension language registration and Zed plumbing
2. TOM-71 Rust server routing and bridge protocol
3. TOM-67 sidecar document store, parser, and template index
4. TOM-69 Spring MVC view and model cross-language index
5. TOM-70 integrate Kotlin-Pebble definition and references
6. TOM-72 fixtures, regression tests, and Zed verification
7. TOM-68 Phase 2 Spring MVC coverage expansion
8. TOM-66 Phase 3 deeper template semantics
9. TOM-73 Phase 4 reliability and release hardening

## Acceptance Criteria

- Local ticket tree captures the full Pebble roadmap.
- Dependency edges encode the intended implementation order.
- `tk ready` points newcomers at the next unblocked issue.
